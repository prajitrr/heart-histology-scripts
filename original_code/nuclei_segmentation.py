import os
import cv2
import numpy as np
import matplotlib.pyplot as plt
from skimage import morphology, measure, exposure
from skimage.color import rgb2gray, label2rgb
import pandas as pd

# Define the root directory containing subfolders with images
root_folder = '/Volumes/LaBo/Test_Folder/filtered_448_to_224_tiless_amyloid'  # Replace with your actual path

# Define the output CSV file path
output_csv_path = '/Volumes/LaBo/Test_Folder/filtered_448_to_224_tiless_amyloid/all_amyloid_nuclei_count.csv'  # Replace with your desired output path


# Define the tissue mask threshold
tissue_threshold = 0.9  # Adjust as needed

# Threshold value for additional masking
threshold_value = 0.2

# Define the HSV range for deep purple color (adjust as needed)
lower_purple = np.array([120, 50, 20])  # Lower bound of purple (Hue, Saturation, Value)
upper_purple = np.array([160, 255, 255])  # Upper bound of purple

# Check if the CSV file already exists
file_exists = os.path.isfile(output_csv_path)

# Initialize an empty list to store data for all images (optional)
# data_list = []  # No longer needed since we're writing to CSV directly

# Define valid image extensions
image_extensions = ('.tif', '.tiff', '.png', '.jpg', '.jpeg')  # Add other extensions if needed

# Traverse the directory tree
print("Starting directory traversal")
for dirpath, dirnames, filenames in os.walk(root_folder):
    print(f"Processing directory: {dirpath}")
    for filename in filenames:
        print(f"Processing file: {filename}")
        if filename.startswith("._"):

            # Remove hidden files or metadata files
            os.remove(os.path.join(dirpath, filename))
            print(f"Removed hidden file: {filename}")
            continue

        if filename.lower().endswith(image_extensions):
            image_path = os.path.join(dirpath, filename)
            try:
                print(f"Loading image: {filename}")
                img = cv2.imread(image_path) # CV2 load image in BGR by default need to swtich to RGB later
                if img is None:
                	print (f"Error: There is no image in {image_path}, bro")
                
                # Extract image data
                image_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB) #switch channels
                image_gray= rgb2gray(image_rgb)  # really important here, i technically also can use the original image and use img to read the do the gray file.
                
                # **Step 1: Create Tissue Mask **
                tissue_mask = image_gray < tissue_threshold  # Tissue pixels are darker than 0.9
                # clean up tissue mask
                tissue_mask = morphology.remove_small_objects(tissue_mask, min_size=500) # remove small non zero values
                tissue_mask = morphology.remove_small_holes(tissue_mask, area_threshold=500) #remove small low values
                tissue_mask = morphology.closing(tissue_mask, morphology.disk(10))
                
                # **Step 2: Apply Tissue Mask to the Image**
                image_data_masked = np.copy(image_rgb)
                image_data_masked[~tissue_mask] = 0  # Set background pixels to zero. ~ means inverse 1 and 0
                # here we convert to HSV channel bc RGB is sensitive to lighting changes, HSV speartes color and is easier to set trehshould for color
                image_hsv = cv2.cvtColor(image_data_masked, cv2.COLOR_RGB2HSV) 
                purple_mask = cv2.inRange(image_hsv, lower_purple, upper_purple) # set up the purple mask to extract nuclei
                
                # Combine tissue mask and purple mask to get final binary mask
                combined_mask = purple_mask > 0
                combined_mask = combined_mask & tissue_mask
                            
                # **Step 3: Enhance Contrast within Tissue Regions**
                image_contrast = rgb2gray(image_data_masked)

                # **Step 4: Apply Additional Thresholding within Tissue Regions**
                p2, p98 = np.percentile(image_contrast[tissue_mask], (2, 98))
                image_contrast = exposure.rescale_intensity(image_contrast, in_range=(p2, p98))
                
                # Add the processed image to the ImageContainer
                binary_mask = image_contrast < threshold_value
                
                final_mask = combined_mask & binary_mask
                
                # **Step 5: clean up final mask**
                final_mask = morphology.remove_small_objects(final_mask, min_size=10)
                final_mask = morphology.remove_small_holes(final_mask, area_threshold=10)
                
                # **Step 6: Label and Count Nuclei**
                labeled_mask = measure.label(final_mask)
                nuclei_count = np.max(labeled_mask)
                
                # **Extract Nuclei Properties**
                properties = ['area', 'perimeter', 'eccentricity', 'solidity', 'orientation']
                nuclei_props = measure.regionprops_table(labeled_mask, intensity_image=image_contrast, properties=properties)
                
                nuclei_df = pd.DataFrame(nuclei_props) # Panda is sooo good with tabular data, each properties will be a column calculated from the labeld mask
                if not nuclei_df.empty:
                    mean_area = nuclei_df['area'].mean()
                    median_eccentricity = nuclei_df['eccentricity'].median()
                    mean_perimeter = nuclei_df['perimeter'].mean()
                    mean_solidity = nuclei_df['solidity'].mean()
                else:
                    # Handle cases with no nuclei detected
                    mean_area = np.nan
                    median_eccentricity = np.nan
                    mean_perimeter = np.nan
                    mean_solidity = np.nan
                
                # **Collect Data for the Current Image**
                image_info = {
                    'filename': filename,
                    'filepath': image_path,
                    'nuclei_count': nuclei_count,
                    'mean_area': mean_area,
                    'median_eccentricity': median_eccentricity,
                    'mean_perimeter': mean_perimeter,
                    'mean_solidity': mean_solidity,
                    # Add more features as needed
                }
                
                # **Write Data to CSV File**
                df_image = pd.DataFrame([image_info])
                
                # Determine if header should be written
                write_header = not file_exists
                df_image.to_csv(output_csv_path, mode='a', index=False, header=write_header)
                
                # After first write, set file_exists to True
                if not file_exists:
                    file_exists = True
                
                # **Visualization (Optional)**
                # Uncomment the following block if you wish to visualize each image's results
                # fig, axes = plt.subplots(1, 4, figsize=(20, 5))
                # axes[0].imshow(img["image"].data.squeeze(), cmap='gray')
                # axes[0].set_title("Original Image")
                # axes[0].axis('off')
                # axes[1].imshow(tissue_mask, cmap='gray')
                # axes[1].set_title("Tissue Mask")
                # axes[1].axis('off')
                # axes[2].imshow(binary_mask, cmap='gray')
                # axes[2].set_title("Binary Mask")
                # axes[2].axis('off')
                # image_label_overlay = label2rgb(labeled_mask, image=img["image"].data.squeeze(), bg_label=0)
                # axes[3].imshow(image_label_overlay)
                # axes[3].set_title("Segmentation Overlay")
                # axes[3].axis('off')
                # plt.tight_layout()
                # plt.show()
                
            except Exception as e:
                print(f"Error processing image {filename}: {e}")
                continue
print("Finished processing")
print(f"Data saved to {output_csv_path}")
