#include <opencv2/opencv.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <string>
#include <ctime>
#include <jni.h>
#include <iostream>
#include <algorithm>

std::string JAR_PATH = "../bioformats_package.jar";
int TILE_WIDTH = 448;
int TILE_HEIGHT = 448;


bool is_mostly_white(const cv::Mat& img, 
                     int threshold = 230, 
                     float white_ratio = 0.5) {
    cv::Mat gray;
    cv::cvtColor(img, gray, cv::COLOR_BGR2GRAY);
    cv::Mat mask = gray > threshold;
    int white = cv::countNonZero(mask);
    return white > white_ratio * img.total();
}

bool is_mostly_green_or_blue(const cv::Mat& img, 
                             int threshold=100, 
                             float color_ratio=0.5, 
                             int margin=10) {
    cv::Mat green, blue, red;
    cv::Mat channels[3];
    cv::split(img, channels);
    red = channels[0];
    green = channels[1];
    blue = channels[2];

    cv::Mat green_mask = (green > threshold) & \
                         (green > red + margin) & \
                         (green > blue + margin);
    cv::Mat blue_mask = (blue > threshold) & \
                        (blue > red + margin) & \
                        (blue > green + margin);
    cv::Mat mask = green_mask | blue_mask;

    int total_pixels = img.total();
    int color_pixels = cv::countNonZero(mask);

    return color_pixels > color_ratio * total_pixels;
}

void tile_image(std::string image_path, std::string output_dir) {
    // Initialize JVM
    JavaVM *jvm;
    JNIEnv *env;
    JavaVMInitArgs vm_args;
    JavaVMOption* options = new JavaVMOption[2];
    options[0].optionString = const_cast<char*>("-Djava.class.path=../bioformats_package.jar" );
    options[1].optionString = const_cast<char*>("-Xmx9g");
    vm_args.version = JNI_VERSION_1_6;
    vm_args.nOptions = 1;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = false;
    jint res = JNI_CreateJavaVM(&jvm, (void**)&env, &vm_args);
    if (res != JNI_OK) {
      std::cerr << "Failed to create Java VM" << std::endl;
      exit(EXIT_FAILURE);
    }
    
    // Get object and method signatures
    jclass cls = env->FindClass("loci/formats/ImageReader");
    jobject obj = env->NewObject(cls, env->GetMethodID(cls, "<init>", "()V"));
    jmethodID setIdMethod = env->GetMethodID(cls, "setId", "(Ljava/lang/String;)V");
    jmethodID getSeriesCountMethod = env->GetMethodID(cls, "getSeriesCount", "()I");
    jmethodID setSeriesMethod = env->GetMethodID(cls, "setSeries", "(I)V");
    jmethodID getSizeXMethod = env->GetMethodID(cls, "getSizeX", "()I");
    jmethodID getSizeYMethod = env->GetMethodID(cls, "getSizeY", "()I");
    jmethodID getSizeCMethod = env->GetMethodID(cls, "getSizeC", "()I");
    jmethodID openBytesMethod = env->GetMethodID(cls, "openBytes", "(I[BIIII)[B");


    // Read in image and get series count
    jstring jImagePath = env->NewStringUTF(image_path.c_str());
    env->CallVoidMethod(obj, setIdMethod, jImagePath);
    env->DeleteLocalRef(jImagePath);
    jint seriesCount = env->CallIntMethod(obj, getSeriesCountMethod);
    std::cout << "Series count: " << seriesCount << std::endl;

    // Set series to highest resolution
    env->CallVoidMethod(obj, setSeriesMethod, 13);
    jint sizeX = env->CallIntMethod(obj, getSizeXMethod);
    jint sizeY = env->CallIntMethod(obj, getSizeYMethod);
    jint sizeC = env->CallIntMethod(obj, getSizeCMethod);


    int size_x = static_cast<int>(sizeX);
    int size_y = static_cast<int>(sizeY);
    int size_c = static_cast<int>(sizeC);

    // Discard 1/4 of image from each edge
    int x_start = size_x / 4;
    int y_start = size_y / 4;
    int x_end = size_x - size_x / 4;
    int y_end = size_y - size_y / 4;

    int tile_width = TILE_WIDTH;
    int tile_height = TILE_HEIGHT;

    std::cout << "Size X: " << size_x << std::endl;
    std::cout << "Size Y: " << size_y << std::endl;
    std::cout << "Size C: " << size_c << std::endl;

    for (int x=x_start; x<x_end; x+=tile_width) {
        for (int y=y_start; y<y_end; y+=tile_height) {

            int width = std::min(tile_width, x_end - x);
            int height = std::min(tile_height, y_end - y);

            // Create buffer of size width * height * size_c

            jbyteArray scratchbuffer = env->NewByteArray(width * height * size_c);
            jbyteArray buffer = (jbyteArray)env->CallObjectMethod(obj, openBytesMethod, 0, scratchbuffer, x, y, width, height);

            if (buffer != scratchbuffer) {
                env->DeleteLocalRef(scratchbuffer);
            }

            jbyte* elements = env->GetByteArrayElements(buffer, nullptr);
            cv::Mat image(1, width * height * size_c, CV_8UC1, elements);
            image = image.reshape(size_c, height);  // Channels, Rows

            // Downscale image to 224 x 224, or use INTER_AREA if image too big
            if (size_x > 224 || size_y > 224) {
              cv::resize(image, image, cv::Size(224, 224), 0, 0, cv::INTER_LANCZOS4);
            }

            // Skipped normalization step

            if (is_mostly_white(image)) {
                //std::cout << "Tile " << x << ", " << y << " is mostly white" << std::endl;
                env->ReleaseByteArrayElements(buffer, elements, 0);
                env->DeleteLocalRef(buffer);      
                continue;
            } else if (is_mostly_green_or_blue(image)) {
                //std::cout << "Tile " << x << ", " << y << " is mostly green or blue" << std::endl;
                env->ReleaseByteArrayElements(buffer, elements, 0);
                env->DeleteLocalRef(buffer);      
                continue;
            }
            else {
                std::string filename = output_dir + "/tile_" + std::to_string(x) + "_" + std::to_string(y) + ".tif";
                std::cout << std::to_string(x) << "_" << std::to_string(y) << std::endl;
                cv::imwrite(filename, image);

            }
            env->ReleaseByteArrayElements(buffer, elements, 0);
            env->DeleteLocalRef(buffer);      
            
            // Save image to disk
            
            
            
        }
    }

    if (obj == nullptr) {
      std::cerr << "Failed to create an instance of ImageReader" << std::endl;
      jvm->DestroyJavaVM();
      exit(EXIT_FAILURE);
    }
    if (cls == nullptr) {
      std::cerr << "Failed to find ImageReader class" << std::endl;
      jvm->DestroyJavaVM();
      exit(EXIT_FAILURE);
    }



    jclass metaCls = env->FindClass("loci/formats/MetadataTools");
    if (metaCls == nullptr) {
      std::cerr << "Failed to find MetadataTools class" << std::endl;
      jvm->DestroyJavaVM();
      exit(EXIT_FAILURE);
    }

    if (res != JNI_OK) {
      std::cerr << "Failed to create Java VM" << std::endl;
      exit(EXIT_FAILURE);
    }
    else {
      std::cout << "Java VM created successfully" << std::endl;
    }
}

int main() {
    //std::string path;
    //std::cin >> path;
    //cv::Mat img = cv::imread(path);
    auto start = std::chrono::high_resolution_clock::now();
    tile_image("../sample_vsi/Image_A-22-00025A4-1.vsi", "./test_out");
    auto end = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsed = end - start;
    std::cout << "Elapsed time: " << elapsed.count() << "s" << std::endl;
    return 0;
}

