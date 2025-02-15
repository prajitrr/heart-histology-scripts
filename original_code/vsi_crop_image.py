import os
import numpy as np
from PIL import Image, ImageOps
import jpype
import jpype.imports
from jpype.types import *
import time

# Define the functions
def normalize_image(image):
    return ImageOps.autocontrast(image)

def is_mostly_white(image, threshold=230, white_ratio=0.5):
    # Convert to grayscale
    gray_image = image.convert('L')
    # Calculate the number of pixels close to white
    close_to_white_pixels = np.sum(np.asarray(gray_image) > threshold)
    # Calculate the total number of pixels
    total_pixels = gray_image.size[0] * gray_image.size[1]
    # Calculate the ratio of pixels close to white
    ratio = close_to_white_pixels / total_pixels
    # Check if the ratio is greater than the white_ratio
    return ratio > white_ratio

def is_mostly_green_or_blue(image, threshold=100, color_ratio=0.5):
    # Convert image to numpy array
    image_array = np.asarray(image)
    # Get R, G, B channels
    R = image_array[:, :, 0]
    G = image_array[:, :, 1]
    B = image_array[:, :, 2]
    
    # Define margin
    margin = 10  # Adjust as necessary
    
    # Create mask for green pixels
    green_mask = (G > threshold) & (G > R + margin) & (G > B + margin)
    
    # Create mask for blue pixels
    blue_mask = (B > threshold) & (B > R + margin) & (B > G + margin)
    
    # Combine masks
    combined_mask = green_mask | blue_mask
    
    # Calculate ratio
    total_pixels = image_array.shape[0] * image_array.shape[1]
    color_pixels = np.sum(combined_mask)
    ratio = color_pixels / total_pixels
    
    # Check if ratio is above the threshold
    return ratio > color_ratio

# Path to the Bio-Formats JAR file
bioformats_jar = "../bioformats_package.jar"  # Update this path to your actual location

# Check if the JAR file exists
if not os.path.isfile(bioformats_jar):
    raise FileNotFoundError(f"Bio-Formats JAR file not found at {bioformats_jar}")


start = time.process_time()
jpype.startJVM(classpath=[bioformats_jar])


# Import Java packages
import loci.formats.ImageReader
import loci.formats.MetadataTools

# Initialize the reader
vsi_file = "../sample_vsi/Image_A-22-00025A4-1.vsi"  # Update this path to your actual image location
output_root_folder = "../python_test_output"
reader = loci.formats.ImageReader()
reader.setId(vsi_file)

# List available series
num_series = reader.getSeriesCount()
print(f"Processing {vsi_file}, Number of series: {num_series}")

for i in range(num_series):
    reader.setSeries(i)
    series_name = reader.getSeries()
    sizeX = reader.getSizeX()
    sizeY = reader.getSizeY()
    print(f"Series {i}: {series_name}, sizeX={sizeX}, sizeY={sizeY}")

# Select the high-resolution series (assume it's the last one, adjust if necessary)
high_res_series_index = 13
reader.setSeries(high_res_series_index)

# Get image dimensions
sizeX = reader.getSizeX()
sizeY = reader.getSizeY()
sizeZ = reader.getSizeZ()
sizeC = reader.getSizeC()
sizeT = reader.getSizeT()

print(f"Selected series: {high_res_series_index}, sizeX={sizeX}, sizeY={sizeY}, sizeZ={sizeZ}, sizeC={sizeC}, sizeT={sizeT}")

# Calculate the starting and ending points to discard 1/6 from each edge
x_start = sizeX // 4
x_end = sizeX - sizeX // 4
y_start = sizeY // 4
y_end = sizeY - sizeY // 4

# Create output folder for this VSI file
vsi_output_folder = os.path.join(output_root_folder, os.path.splitext(os.path.basename(vsi_file))[0])
os.makedirs(vsi_output_folder, exist_ok=True)

# Extract and save 448x448 tiles
tile_width = 448
tile_height = 448

for x in range(x_start, x_end, tile_width):
    for y in range(y_start, y_end, tile_height):
        # Ensure the region is within bounds
        width = min(tile_width, x_end - x)
        height = min(tile_height, y_end - y)
        
        # Read the image region
        try:
            image_buffer = reader.openBytes(0, x, y, width, height)
        except Exception as e:
            print(f"Error reading tile at position ({x}, {y}): {e}")
            continue

        # The buffer size should be width * height * sizeC
        expected_size = width * height * sizeC
        if len(image_buffer) != expected_size:
            print(f"Warning: Buffer size {len(image_buffer)} does not match expected size {expected_size}. Skipping tile at position ({x}, {y}).")
            continue

        # Reshape the buffer to (height, width, sizeC)
        image = np.frombuffer(image_buffer, dtype=np.uint8).reshape((height, width, sizeC))

        # Convert to PIL image
        img = Image.fromarray(image, 'RGB')

        # Downsample the image to 224x224 if necessary
        if (width, height) != (224, 224):
            img = img.resize((224, 224), Image.LANCZOS)

        # Normalize the image
        img_normalized = normalize_image(img)

        # Check if the tile is mostly white
        if is_mostly_white(img_normalized):
            print(f"Tile at position ({x}, {y}) is mostly white, skipping.")
            continue

        # Check if the tile is mostly green or blue
        elif is_mostly_green_or_blue(img_normalized):
            print(f"Tile at position ({x}, {y}) is mostly green or blue, skipping.")
            continue

        else:
            # Save the image
            tile_name = f"{os.path.basename(os.path.splitext(vsi_file)[0])}_{x}_{y}.tiff"
            tile_path = os.path.join(vsi_output_folder, tile_name)
            img_normalized.save(tile_path)
            print(f"Tile saved: {tile_path}")

print(f"Processing time: {time.process_time() - start} seconds")
