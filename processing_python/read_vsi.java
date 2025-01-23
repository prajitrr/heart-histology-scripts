import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import javax.swing.plaf.synth.Region;

private byte[] decodeTile(int no, int row, int col)
    throws FormatException, IOException
  {
    if (tileMap.get(getCoreIndex()) == null) {
      return new byte[getTileSize()];
    }

    int[] zct = getZCTCoords(no);
    TileCoordinate t = new TileCoordinate(nDimensions.get(getCoreIndex()));
    t.coordinate[0] = col;
    t.coordinate[1] = row;

    int resIndex = getResolutionIndex();
    Pyramid pyramid = getCurrentPyramid();

    for (String dim : pyramid.dimensionOrdering.keySet()) {
      int index = pyramid.dimensionOrdering.get(dim) + 2;

      if (dim.equals("Z")) {
        t.coordinate[index] = zct[0];
      }
      else if (dim.equals("C")) {
        t.coordinate[index] = zct[1];
      }
      else if (dim.equals("T")) {
        t.coordinate[index] = zct[2];
      }
    }

    if (resIndex > 0) {
      t.coordinate[t.coordinate.length - 1] = resIndex;
    }

    ArrayList<TileCoordinate> map = tileMap.get(getCoreIndex());
    Integer index = map.indexOf(t);
    if (index == null || index < 0) {
      // fill in the tile with the stored background color
      // usually this is either black or white
      byte[] tile = new byte[getTileSize()];
      byte[] color = backgroundColor.get(getCoreIndex());
      if (color != null) {
        for (int q=0; q<getTileSize(); q+=color.length) {
          for (int i=0; i<color.length; i++) {
            tile[q + i] = color[i];
          }
        }
      }
      return tile;
    }

    Long offset = tileOffsets.get(getCoreIndex())[index];
    byte[] buf = null;
    IFormatReader reader = null;
    try (RandomAccessInputStream ets =
      new RandomAccessInputStream(fileMap.get(getCoreIndex()))) {
      ets.seek(offset);
      CodecOptions options = new CodecOptions();
      options.interleaved = isInterleaved();
      options.littleEndian = isLittleEndian();
      int tileSize = getTileSize();
      if (tileSize == 0) {
        tileSize = tileX.get(getCoreIndex()) * tileY.get(getCoreIndex()) * 10;
      }

      long end = index < tileOffsets.get(getCoreIndex()).length - 1 ?
        tileOffsets.get(getCoreIndex())[index + 1] : ets.length();

      int compression = compressionType.get(getCoreIndex());
      int compressedBufSize = compression == PNG || compression == BMP ?
        (int) (end - offset) : tileSize;
      byte[] compressedBuf = new byte[compressedBufSize];
      ets.read(compressedBuf);

      String file = null;

      switch (compression) {
        case RAW:
          buf = compressedBuf;
          break;
        case JPEG:
          Codec codec = new JPEGCodec();
          buf = codec.decompress(compressedBuf, options);
          break;
        case JPEG_2000:
          codec = new JPEG2000Codec();
          buf = codec.decompress(compressedBuf, options);
          break;
        case JPEG_LOSSLESS:
          codec = new LosslessJPEGCodec();
          buf = codec.decompress(compressedBuf, options);
          break;
        case PNG:
          file = "tile.png";
          reader = new APNGReader();
        case BMP:
          if (reader == null) {
            file = "tile.bmp";
            reader = new BMPReader();
          }

          Location.mapFile(file, new ByteArrayHandle(compressedBuf));
          reader.setId(file);
          buf = reader.openBytes(0);
          Location.mapFile(file, null);
          break;
      }
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
    return buf;
  }

  private boolean parseETSFile(RandomAccessInputStream etsFile, String file, int s,
                            boolean hasOrphanEtsFiles)
    throws FormatException, IOException
  {
    fileMap.put(core.size() - 1, file);

    etsFile.order(true);

    CoreMetadata ms = core.get(getCoreIndex());

    // read the volume header
    String magic = etsFile.readString(4).trim();
    if (!magic.equals("SIS")) {
      throw new FormatException("Unknown magic bytes: " + magic);
    }

    int headerSize = etsFile.readInt();
    int version = etsFile.readInt();
    nDimensions.add(etsFile.readInt());
    long additionalHeaderOffset = etsFile.readLong();
    int additionalHeaderSize = etsFile.readInt();
    etsFile.skipBytes(4); // reserved
    long usedChunkOffset = etsFile.readLong();
    int nUsedChunks = etsFile.readInt();
    etsFile.skipBytes(4); // reserved

    // read the additional header
    etsFile.seek(additionalHeaderOffset);

    String moreMagic = etsFile.readString(4).trim();
    if (!moreMagic.equals("ETS")) {
      throw new FormatException("Unknown magic bytes: " + moreMagic);
    }

    etsFile.skipBytes(4); // extra version number

    int pixelType = etsFile.readInt();
    ms.sizeC = etsFile.readInt();
    int colorspace = etsFile.readInt();
    compressionType.add(etsFile.readInt());
    int compressionQuality = etsFile.readInt();
    tileX.add(etsFile.readInt());
    tileY.add(etsFile.readInt());
    int tileZ = etsFile.readInt();
    etsFile.skipBytes(4 * 17); // pixel info hints

    byte[] color = new byte[
      ms.sizeC * FormatTools.getBytesPerPixel(convertPixelType(pixelType))];
    etsFile.read(color);

    backgroundColor.put(getCoreIndex(), color);

    etsFile.skipBytes(4 * 10 - color.length); // background color
    int componentOrder = etsFile.readInt();
    bgr[s] = componentOrder == 1 && compressionType.get(compressionType.size() - 1) == RAW;
    boolean usePyramid = etsFile.readInt() != 0;

    ms.rgb = ms.sizeC > 1;

    // read the used chunks

    etsFile.seek(usedChunkOffset);

    tileOffsets.add(new Long[nUsedChunks]);

    ArrayList<TileCoordinate> tmpTiles = new ArrayList<TileCoordinate>();

    for (int chunk=0; chunk<nUsedChunks; chunk++) {
      etsFile.skipBytes(4);
      int dimensions = nDimensions.get(nDimensions.size() - 1);
      TileCoordinate t = new TileCoordinate(dimensions);
      for (int i=0; i<dimensions; i++) {
        t.coordinate[i] = etsFile.readInt();
      }
      tileOffsets.get(tileOffsets.size() - 1)[chunk] = etsFile.readLong();
      int nBytes = etsFile.readInt();
      etsFile.skipBytes(4);

      tmpTiles.add(t);
    }

    int maxResolution = 0;

    if (usePyramid) {
      for (TileCoordinate t : tmpTiles) {
        if (t.coordinate[t.coordinate.length - 1] > maxResolution) {
          maxResolution = t.coordinate[t.coordinate.length - 1];
        }
      }
    }

    maxResolution++;

    int[] maxX = new int[maxResolution];
    int[] maxY = new int[maxResolution];
    int[] maxZ = new int[maxResolution];
    int[] maxC = new int[maxResolution];
    int[] maxT = new int[maxResolution];

    HashMap<String, Integer> dimOrder = new HashMap<String, Integer>();
    Pyramid pyramid = null;
    /**
    * If there are orphaned .ets files with this vsi file, we need to determine whether
    * the current one is an orphan or a legit file.  The logic to determine this is to
    * see of there is a metadata block (ie 'pyramid') whose width and height are
    * within the correct range for this .ets file.  If there is no matching metadata
    * block, then we have to assume this is an orphan
    **/
    if (hasOrphanEtsFiles) {
      int maxXAtRes0 = 0;
      int maxYAtRes0 = 0;
      for (TileCoordinate t : tmpTiles) {
        if (!usePyramid || t.coordinate[t.coordinate.length - 1] == 0) {
          maxXAtRes0 = Math.max(maxXAtRes0, t.coordinate[0]);
          maxYAtRes0 = Math.max(maxYAtRes0, t.coordinate[1]);
        }
      }
      int maxPixelWidth  = (maxXAtRes0 + 1) * tileX.get(tileX.size()-1);
      int maxPixelHeight = (maxYAtRes0 + 1) * tileY.get(tileY.size()-1);
      for (Pyramid p : pyramids) {
        if (p.HasAssociatedEtsFile) // If this pyramid has already been linked to an ETS
          continue;                 // then don't allow it to be linked to another.
        if (  (p.width  <= maxPixelWidth ) && (p.width  >= maxPixelWidth  - tileX.get(tileX.size()-1))
           && (p.height <= maxPixelHeight) && (p.height >= maxPixelHeight - tileY.get(tileY.size()-1)) ) {
            pyramid = p;
            p.HasAssociatedEtsFile = true; // Rememeber that this pyramid is now taken by an Ets.
            break;
        }
      }
      /**
      * No matching metadata block.  This is an orphan ets file.  Undo and erase 
      * all the data elements that have been gathered up for this .ets file.
      **/
      if (pyramid == null) {
        fileMap.remove(core.size() - 1);
        nDimensions.remove(nDimensions.size() - 1);
        compressionType.remove(compressionType.size() - 1);
        tileX.remove(tileX.size() - 1);
        tileY.remove(tileY.size() - 1);
        backgroundColor.remove(getCoreIndex());
        tileOffsets.remove(tileOffsets.size()-1);
        return(false);
      }
    }
    else {
      pyramid = pyramids.get(s);
    }
    dimOrder = pyramid.dimensionOrdering;

    for (TileCoordinate t : tmpTiles) {
      int resolution = usePyramid ? t.coordinate[t.coordinate.length - 1] : 0;

      Integer tv = dimOrder.get("T");
      Integer zv = dimOrder.get("Z");
      Integer cv = dimOrder.get("C");

      int tIndex = tv == null ? -1 : tv + 2;
      int zIndex = zv == null ? -1 : zv + 2;
      int cIndex = cv == null ? -1 : cv + 2;

      if (usePyramid && tIndex == t.coordinate.length - 1) {
        tv = null;
        tIndex = -1;
      }
      if (usePyramid && zIndex == t.coordinate.length - 1) {
        zv = null;
        zIndex = -1;
      }

      int upperLimit = usePyramid ? t.coordinate.length - 1 : t.coordinate.length;
      if ((tIndex < 0 || tIndex >= upperLimit) &&
        (zIndex < 0 || zIndex >= upperLimit) &&
        (cIndex < 0 || cIndex >= upperLimit))
      {
        tIndex--;
        zIndex--;
        cIndex--;
        if (dimOrder.containsKey("T")) {
          dimOrder.put("T", tIndex - 2);
        }
        if (dimOrder.containsKey("Z")) {
          dimOrder.put("Z", zIndex - 2);
        }
        if (dimOrder.containsKey("C")) {
          dimOrder.put("C", cIndex - 2);
        }
      }

      if (tv == null && zv == null) {
        if (t.coordinate.length > 4 && cv == null) {
          cIndex = 2;
          dimOrder.put("C", cIndex - 2);
        }

        if (t.coordinate.length > 4) {
          if (cv == null) {
            tIndex = 3;
          }
          else {
            tIndex = cIndex + 2;
          }
          if (tIndex < t.coordinate.length) {
            dimOrder.put("T", tIndex - 2);
          }
          else {
            tIndex = -1;
          }
        }

        if (t.coordinate.length > 5) {
          if (cv == null) {
            zIndex = 4;
          }
          else {
            zIndex = cIndex + 1;
          }
          if (zIndex < t.coordinate.length) {
            dimOrder.put("Z", zIndex - 2);
          }
          else {
            zIndex = -1;
          }
        }
      }

      if (t.coordinate[0] > maxX[resolution]) {
        maxX[resolution] = t.coordinate[0];
      }
      if (t.coordinate[1] > maxY[resolution]) {
        maxY[resolution] = t.coordinate[1];
      }

      if (tIndex >= 0 && t.coordinate[tIndex] > maxT[resolution]) {
        maxT[resolution] = t.coordinate[tIndex];
      }
      if (zIndex >= 0 && t.coordinate[zIndex] > maxZ[resolution]) {
        maxZ[resolution] = t.coordinate[zIndex];
      }
      if (cIndex >= 0 && t.coordinate[cIndex] > maxC[resolution]) {
        maxC[resolution] = t.coordinate[cIndex];
      }
    }
    ms.sizeX = pyramid.width;
    ms.sizeY = pyramid.height;
    ms.seriesMetadata = pyramid.originalMetadata;
    ms.sizeZ = maxZ[0] + 1;
    if (maxC[0] > 0) {
      ms.sizeC *= (maxC[0] + 1);
    }
    ms.sizeT = maxT[0] + 1;
    if (ms.sizeZ == 0) {
      ms.sizeZ = 1;
    }
    ms.imageCount = ms.sizeZ * ms.sizeT;
    if (maxC[0] > 0) {
      ms.imageCount *= (maxC[0] + 1);
    }

    if (maxY[0] >= 1) {
      rows.add(maxY[0] + 1);
    }
    else {
      rows.add(1);
    }
    if (maxX[0] >= 1) {
      cols.add(maxX[0] + 1);
    }
    else {
      cols.add(1);
    }

    ArrayList<TileCoordinate> map = new ArrayList<TileCoordinate>();
    for (int i=0; i<tmpTiles.size(); i++) {
      map.add(tmpTiles.get(i));
    }
    tileMap.add(map);

    ms.pixelType = convertPixelType(pixelType);
    if (usePyramid) {
      int finalResolution = 1;
      int initialCoreSize = core.size();
      for (int i=1; i<maxResolution; i++) {
        CoreMetadata newResolution = new CoreMetadata(ms);

        int previousX = core.get(core.size() - 1).sizeX;
        int previousY = core.get(core.size() - 1).sizeY;
        int maxSizeX = tileX.get(tileX.size() - 1) * (maxX[i] < 1 ? 1 : maxX[i] + 1);
        int maxSizeY = tileY.get(tileY.size() - 1) * (maxY[i] < 1 ? 1 : maxY[i] + 1);

        newResolution.sizeX = previousX / 2;
        if (previousX % 2 == 1 && newResolution.sizeX < maxSizeX) {
          newResolution.sizeX++;
        }
        else if (newResolution.sizeX > maxSizeX) {
          newResolution.sizeX = maxSizeX;
        }
        newResolution.sizeY = previousY / 2;
        if (previousY % 2 == 1 && newResolution.sizeY < maxSizeY) {
          newResolution.sizeY++;
        }
        else if (newResolution.sizeY > maxSizeY) {
          newResolution.sizeY = maxSizeY;
        }
        newResolution.sizeZ = maxZ[i] + 1;
        if (maxC[i] > 0 && newResolution.sizeC != (maxC[i] + 1)) {
          newResolution.sizeC *= (maxC[i] + 1);
        }
        newResolution.sizeT = maxT[i] + 1;
        if (newResolution.sizeZ == 0) {
          newResolution.sizeZ = 1;
        }
        newResolution.imageCount = newResolution.sizeZ * newResolution.sizeT;
        if (maxC[i] > 0) {
          newResolution.imageCount *= (maxC[i] + 1);
        }

        newResolution.metadataComplete = true;
        newResolution.dimensionOrder = "XYCZT";

        core.add(newResolution);

        rows.add(maxY[i] >= 1 ? maxY[i] + 1 : 1);
        cols.add(maxX[i] >= 1 ? maxX[i] + 1 : 1);

        fileMap.put(core.size() - 1, file);
        finalResolution = core.size() - initialCoreSize + 1;

        tileX.add(tileX.get(tileX.size() - 1));
        tileY.add(tileY.get(tileY.size() - 1));
        compressionType.add(compressionType.get(compressionType.size() - 1));
        tileMap.add(map);
        nDimensions.add(nDimensions.get(nDimensions.size() - 1));
        tileOffsets.add(tileOffsets.get(tileOffsets.size() - 1));
        backgroundColor.put(core.size() - 1, color);
      }

      ms.resolutionCount = finalResolution;
    }
    return(true);
  }

  private int convertPixelType(int pixelType) throws FormatException {
    switch (pixelType) {
      case CHAR:
        return FormatTools.INT8;
      case UCHAR:
        return FormatTools.UINT8;
      case SHORT:
        return FormatTools.INT16;
      case USHORT:
        return FormatTools.UINT16;
      case INT:
        return FormatTools.INT32;
      case UINT:
        return FormatTools.UINT32;
      case LONG:
        throw new FormatException("Unsupported pixel type: long");
      case ULONG:
        throw new FormatException("Unsupported pixel type: unsigned long");
      case FLOAT:
        return FormatTools.FLOAT;
      case DOUBLE:
        return FormatTools.DOUBLE;
      default:
        throw new FormatException("Unsupported pixel type: " + pixelType);
    }
  }















public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
  throws FormatException, IOException
{
  FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

  if (getCoreIndex() < core.size() - 1 && getCoreIndex() < rows.size()) {
    int tileRows = rows.get(getCoreIndex());
    int tileCols = cols.get(getCoreIndex());

    Region image = new Region(x, y, w, h);
    int outputRow = 0, outputCol = 0;
    Region intersection = null;

    byte[] tileBuf = null;
    int bpp = FormatTools.getBytesPerPixel(getPixelType());
    int pixel = getRGBChannelCount() * bpp;
    int outputRowLen = w * pixel;

    Pyramid pyramid = getCurrentPyramid();

    for (int row=0; row<tileRows; row++) {
      for (int col=0; col<tileCols; col++) {
        int width = tileX.get(getCoreIndex());
        int height = tileY.get(getCoreIndex());
        Region tile = new Region(col * width, row * height, width, height);

        // the pixel data in the stored tiles may be larger than the defined image size
        // the "tile origin" information indicates how to crop the pixel data
        if (pyramid.tileOriginX != null && pyramid.tileOriginY != null) {
          int resScale = (int) Math.pow(2, getResolutionIndex());
          tile.x += (pyramid.tileOriginX / resScale);
          tile.y += (pyramid.tileOriginY / resScale);
        }

        if (!tile.intersects(image)) {
          continue;
        }

        intersection = tile.intersection(image);
        int intersectionX = 0;

        if (tile.x < image.x) {
          intersectionX = image.x - tile.x;
        }

        tileBuf = decodeTile(no, row, col);

        int rowLen = pixel * (int) Math.min(intersection.width, width);

        int outputOffset = outputRow * outputRowLen + outputCol;
        for (int trow=0; trow<intersection.height; trow++) {
          int realRow = trow + intersection.y - tile.y;
          int inputOffset = pixel * (realRow * width + intersectionX);
          System.arraycopy(tileBuf, inputOffset, buf, outputOffset, rowLen);
          outputOffset += outputRowLen;
        }

        outputCol += rowLen;
      }

      if (intersection != null) {
        outputRow += intersection.height;
        outputCol = 0;
      }
    }

    if (bgr[getCurrentPyramidIndex()]) {
      ImageTools.bgrToRgb(buf, isInterleaved(), bpp, getRGBChannelCount());
    }

    return buf;
  }
  else {
    return parser.getSamples(ifds.get(getIFDIndex() + no), buf, x, y, w, h);
  }
}


public int getRGBChannelCount() {
  int effSizeC = getEffectiveSizeC();
  if (effSizeC == 0) return 0;
  return getSizeC() / effSizeC;
}

public int getSizeC() {
    FormatTools.assertId(currentId, true, 1);
    return getCurrentCore().sizeC;
}

public int getEffectiveSizeC() {
    // NB: by definition, imageCount == effectiveSizeC * sizeZ * sizeT
    int sizeZT = getSizeZ() * getSizeT();
    if (sizeZT == 0) return 0;
    return getImageCount() / sizeZT;
}



private byte[] decodeTile(int no, int row, int col)
    throws FormatException, IOException
  {
    if (tileMap.get(getCoreIndex()) == null) {
      return new byte[getTileSize()];
    }

    int[] zct = getZCTCoords(no);
    TileCoordinate t = new TileCoordinate(nDimensions.get(getCoreIndex()));
    t.coordinate[0] = col;
    t.coordinate[1] = row;

    int resIndex = getResolutionIndex();
    Pyramid pyramid = getCurrentPyramid();

    for (String dim : pyramid.dimensionOrdering.keySet()) {
      int index = pyramid.dimensionOrdering.get(dim) + 2;

      if (dim.equals("Z")) {
        t.coordinate[index] = zct[0];
      }
      else if (dim.equals("C")) {
        t.coordinate[index] = zct[1];
      }
      else if (dim.equals("T")) {
        t.coordinate[index] = zct[2];
      }
    }

    if (resIndex > 0) {
      t.coordinate[t.coordinate.length - 1] = resIndex;
    }

    ArrayList<TileCoordinate> map = tileMap.get(getCoreIndex());
    Integer index = map.indexOf(t);
    if (index == null || index < 0) {
      // fill in the tile with the stored background color
      // usually this is either black or white
      byte[] tile = new byte[getTileSize()];
      byte[] color = backgroundColor.get(getCoreIndex());
      if (color != null) {
        for (int q=0; q<getTileSize(); q+=color.length) {
          for (int i=0; i<color.length; i++) {
            tile[q + i] = color[i];
          }
        }
      }
      return tile;
    }

    Long offset = tileOffsets.get(getCoreIndex())[index];
    byte[] buf = null;
    IFormatReader reader = null;
    try (RandomAccessInputStream ets =
      new RandomAccessInputStream(fileMap.get(getCoreIndex()))) {
      ets.seek(offset);
      CodecOptions options = new CodecOptions();
      options.interleaved = isInterleaved();
      options.littleEndian = isLittleEndian();
      int tileSize = getTileSize();
      if (tileSize == 0) {
        tileSize = tileX.get(getCoreIndex()) * tileY.get(getCoreIndex()) * 10;
      }

      long end = index < tileOffsets.get(getCoreIndex()).length - 1 ?
        tileOffsets.get(getCoreIndex())[index + 1] : ets.length();

      int compression = compressionType.get(getCoreIndex());
      int compressedBufSize = compression == PNG || compression == BMP ?
        (int) (end - offset) : tileSize;
      byte[] compressedBuf = new byte[compressedBufSize];
      ets.read(compressedBuf);

      String file = null;

      switch (compression) {
        case RAW:
          buf = compressedBuf;
          break;
        case JPEG:
          Codec codec = new JPEGCodec();
          buf = codec.decompress(compressedBuf, options);
          break;
        case JPEG_2000:
          codec = new JPEG2000Codec();
          buf = codec.decompress(compressedBuf, options);
          break;
        case JPEG_LOSSLESS:
          codec = new LosslessJPEGCodec();
          buf = codec.decompress(compressedBuf, options);
          break;
        case PNG:
          file = "tile.png";
          reader = new APNGReader();
        case BMP:
          if (reader == null) {
            file = "tile.bmp";
            reader = new BMPReader();
          }

          Location.mapFile(file, new ByteArrayHandle(compressedBuf));
          reader.setId(file);
          buf = reader.openBytes(0);
          Location.mapFile(file, null);
          break;
      }
    } finally {
      if (reader != null) {
        reader.close();
      }
    }
    return buf;
  }
