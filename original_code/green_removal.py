import os
from PIL import Image, ImageOps, ImageStat, UnidentifiedImageError
import numpy as np
import cv2

# Define input folder
input_root_folder = "/Volumes/LaBo/Test_Folder/filtered_448_to_224_tiless_amyloid/A18_00063A5"

# Function to analyze an image and determine if it contains mostly pink or white and has sufficient color variation
def is_pink_or_white_with_variation(image, pink_ratio_threshold=0.5, variation_threshold=20):
    # Convert to numpy array for color analysis
    image_array = np.array(image)

    # Convert to HSV color space to better differentiate colors
    hsv_image = cv2.cvtColor(image_array, cv2.COLOR_RGB2HSV)

    # Define ranges for pink and white in HSV
    # Pink color range
    lower_pink = np.array([150, 50, 50])  # Lower bound of pink (adjust as needed)
    upper_pink = np.array([180, 255, 255])  # Upper bound of pink (adjust as needed)

    # White color range
    lower_white = np.array([0, 0, 200])  # Lower bound of white
    upper_white = np.array([180, 55, 255])  # Upper bound of white

    # Create masks for pink and white
    pink_mask = cv2.inRange(hsv_image, lower_pink, upper_pink)
    white_mask = cv2.inRange(hsv_image, lower_white, upper_white)

    # Calculate the ratio of pink and white pixels
    pink_white_mask = pink_mask | white_mask
    pink_white_ratio = np.sum(pink_white_mask > 0) / (hsv_image.shape[0] * hsv_image.shape[1])

    # Calculate color variation using standard deviation
    stat = ImageStat.Stat(image)
    stddev = stat.stddev[:3]  # RGB standard deviations
    avg_stddev = sum(stddev) / len(stddev)

    # Check if the ratio of pink/white pixels exceeds the threshold and if color variation is sufficient
    return pink_white_ratio > pink_ratio_threshold and avg_stddev > variation_threshold

# Iterate over all folders and images in the input root folder
for root, _, files in os.walk(input_root_folder):
    for filename in files:
        if filename.startswith("._"):
            # Remove hidden files or metadata files
            os.remove(os.path.join(root, filename))
            print(f"Removed hidden file: {filename}")
            continue

        if filename.endswith(".tiff") or filename.endswith(".tif"):
            image_path = os.path.join(root, filename)
            try:
                image = Image.open(image_path)
            except UnidentifiedImageError:
                # Remove files that cannot be identified as images
                os.remove(image_path)
                print(f"Removed unidentified image file: {filename}")
                continue

            # Determine if the image contains mostly pink or white with sufficient variation
            if is_pink_or_white_with_variation(image):
                print(f"Tissue image retained: {filename}")
            else:
                # Remove the noisy image
                os.remove(image_path)
                print(f"Noise image removed: {filename}")

print("Image classification and cleanup based on color and variation completed.")