#include <opencv2/opencv.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <string>
#include <ctime>
#include <jni.h>
#include <iostream>
#include <algorithm>
#include <omp.h>
#include <jni.h>
#include <queue>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <atomic>

extern JavaVM *jvm;
std::string JAR_PATH = "../bioformats_package.jar";
int TILE_WIDTH = 448;
int TILE_HEIGHT = 448;

struct TileWriteTask {
    cv::Mat image;
    std::string filename;
};

// Global thread-safe queue variables.
std::queue<TileWriteTask> writeQueue;
std::mutex queueMutex;
std::condition_variable queueCV;
std::atomic<bool> writingDone(false);  // Flag to signal when processing is complete

// The I/O thread function that will write tiles to disk.
void ioThreadFunc() {
    while (true) {
        std::unique_lock<std::mutex> lock(queueMutex);
        // Wait until there is work or the processing is done.
        queueCV.wait(lock, [] { return !writeQueue.empty() || writingDone.load(); });

        // Process all queued tasks.
        while (!writeQueue.empty()) {
            TileWriteTask task = std::move(writeQueue.front());
            writeQueue.pop();
            // Unlock while doing I/O.
            lock.unlock();
            // Write the image to disk.
            if (!cv::imwrite(task.filename, task.image)) {
                std::cerr << "Error writing file: " << task.filename << std::endl;
            }
            lock.lock();
        }
        // Exit if processing is complete and queue is empty.
        if (writingDone.load() && writeQueue.empty())
            break;
    }
}

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
    // ================================
    // Create the Java VM (in the master thread)
    // ================================
    JavaVM *jvm;
    JNIEnv *env;
    JavaVMInitArgs vm_args;
    // We create an options array; note that the number of options must match vm_args.nOptions.
    JavaVMOption options[2];
    options[0].optionString = const_cast<char*>("-Djava.class.path=../bioformats_package.jar");
    options[1].optionString = const_cast<char*>("-Xmx9g");
    vm_args.version = JNI_VERSION_1_6;
    vm_args.nOptions = 2;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = false;

    jint res = JNI_CreateJavaVM(&jvm, (void**)&env, &vm_args);
    if (res != JNI_OK) {
        std::cerr << "Failed to create Java VM" << std::endl;
        exit(EXIT_FAILURE);
    }

    // ================================
    // Do one-time work (in master thread) to get image dimensions.
    // You might use a temporary ImageReader to query image size.
    // ================================
    jclass cls = env->FindClass("loci/formats/ImageReader");
    if (!cls) {
        std::cerr << "Failed to find ImageReader class" << std::endl;
        exit(EXIT_FAILURE);
    }
    jmethodID constructor = env->GetMethodID(cls, "<init>", "()V");
    jobject globalReader = env->NewObject(cls, constructor);

    jmethodID setIdMethod = env->GetMethodID(cls, "setId", "(Ljava/lang/String;)V");
    jstring jImagePath = env->NewStringUTF(image_path.c_str());
    env->CallVoidMethod(globalReader, setIdMethod, jImagePath);
    env->DeleteLocalRef(jImagePath);

    jmethodID getSeriesCountMethod = env->GetMethodID(cls, "getSeriesCount", "()I");
    jint seriesCount = env->CallIntMethod(globalReader, getSeriesCountMethod);
    std::cout << "Series count: " << seriesCount << std::endl;

    // Select the desired series (here, series 13)
    jmethodID setSeriesMethod = env->GetMethodID(cls, "setSeries", "(I)V");
    env->CallVoidMethod(globalReader, setSeriesMethod, 13);

    jmethodID getSizeXMethod = env->GetMethodID(cls, "getSizeX", "()I");
    jmethodID getSizeYMethod = env->GetMethodID(cls, "getSizeY", "()I");
    jmethodID getSizeCMethod = env->GetMethodID(cls, "getSizeC", "()I");

    jint sizeX = env->CallIntMethod(globalReader, getSizeXMethod);
    jint sizeY = env->CallIntMethod(globalReader, getSizeYMethod);
    jint sizeC = env->CallIntMethod(globalReader, getSizeCMethod);
    std::cout << "Size X: " << sizeX << std::endl;
    std::cout << "Size Y: " << sizeY << std::endl;
    std::cout << "Size C: " << sizeC << std::endl;

    // Convert to local ints
    int size_x = static_cast<int>(sizeX);
    int size_y = static_cast<int>(sizeY);
    int size_c = static_cast<int>(sizeC);

    // Compute region to process (discard 1/4 of image from each edge)
    int x_start = size_x / 4;
    int y_start = size_y / 4;
    int x_end = size_x - size_x / 4;
    int y_end = size_y - size_y / 4;

    std::thread ioThread(ioThreadFunc);

    // Release the globalReader (it was only needed to compute image dimensions)
    env->DeleteLocalRef(globalReader);

    // ================================
    // Parallel region: Each thread attaches to the JVM and creates its own ImageReader.
    // ================================
    #pragma omp parallel
    {
        // Each thread must attach to the JVM to get its own JNIEnv pointer.
        JNIEnv* threadEnv = nullptr;
        if (jvm->AttachCurrentThread((void**)&threadEnv, nullptr) != JNI_OK) {
            std::cerr << "Thread failed to attach to JVM" << std::endl;
            return;  // Skip this thread if attachment fails.
        }

        // Each thread creates its own instance of ImageReader.
        jclass threadCls = threadEnv->FindClass("loci/formats/ImageReader");
        if (!threadCls) {
            std::cerr << "Thread failed to find ImageReader class" << std::endl;
            jvm->DetachCurrentThread();
            return;
        }
        jmethodID threadConstructor = threadEnv->GetMethodID(threadCls, "<init>", "()V");
        jobject threadReader = threadEnv->NewObject(threadCls, threadConstructor);
        if (!threadReader) {
            std::cerr << "Thread failed to create ImageReader instance" << std::endl;
            jvm->DetachCurrentThread();
            return;
        }

        // Set the image path and series for this thread's ImageReader.
        jmethodID threadSetIdMethod = threadEnv->GetMethodID(threadCls, "setId", "(Ljava/lang/String;)V");
        jstring threadJImagePath = threadEnv->NewStringUTF(image_path.c_str());
        threadEnv->CallVoidMethod(threadReader, threadSetIdMethod, threadJImagePath);
        threadEnv->DeleteLocalRef(threadJImagePath);

        jmethodID threadSetSeriesMethod = threadEnv->GetMethodID(threadCls, "setSeries", "(I)V");
        threadEnv->CallVoidMethod(threadReader, threadSetSeriesMethod, 13);

        // Get the openBytes method for tile extraction.
        jmethodID openBytesMethod = threadEnv->GetMethodID(threadCls, "openBytes", "(I[BIIII)[B");

        // Now parallelize the nested loops over tile coordinates.
        // The collapse(2) clause tells OpenMP to merge the two loops.
        #pragma omp for collapse(2) schedule(dynamic)
        for (int x = x_start; x < x_end; x += TILE_WIDTH) {
            for (int y = y_start; y < y_end; y += TILE_HEIGHT) {
                // Determine the actual tile size (may be smaller at image boundaries)
                int width  = std::min(TILE_WIDTH, x_end - x);
                int height = std::min(TILE_HEIGHT, y_end - y);

                // Create a scratch buffer for the tile data.
                jbyteArray scratchbuffer = threadEnv->NewByteArray(width * height * size_c);
                // Call the openBytes method to read the tile
                jbyteArray buffer = (jbyteArray)
                    threadEnv->CallObjectMethod(threadReader, openBytesMethod, 0, scratchbuffer, x, y, width, height);
                // If a new array was returned, free the scratch buffer.
                if (buffer != scratchbuffer) {
                    threadEnv->DeleteLocalRef(scratchbuffer);
                }

                // Access the byte data and wrap it in an OpenCV Mat.
                jbyte* elements = threadEnv->GetByteArrayElements(buffer, nullptr);
                cv::Mat image(1, width * height * size_c, CV_8UC1, elements);
                image = image.reshape(size_c, height);  // reshape into proper rows and channels

                // Downscale the image if needed.
                if (size_x > 224 || size_y > 224) {
                    cv::resize(image, image, cv::Size(224, 224), 0, 0, cv::INTER_LANCZOS4);
                }

                // Skip tiles that are mostly white or mostly green/blue.
                if (is_mostly_white(image) || is_mostly_green_or_blue(image)) {
                    threadEnv->ReleaseByteArrayElements(buffer, elements, 0);
                    threadEnv->DeleteLocalRef(buffer);
                    continue;
                }

                // Save the tile to disk.
                std::string filename = output_dir + "/tile_" +
                                       std::to_string(x) + "_" + std::to_string(y) + ".tif";
                
                // std::cout << "Tiled: " << filename << std::endl;
                TileWriteTask task;
                task.image = image;
                task.filename = filename;

                {
                    std::lock_guard<std::mutex> lock(queueMutex);
                    writeQueue.push(std::move(task));
                }
                queueCV.notify_one();  // Wake up the I/O thread.


                threadEnv->ReleaseByteArrayElements(buffer, elements, 0);
                threadEnv->DeleteLocalRef(buffer);
            }  // end inner loop
        }  // end outer loop

        // Detach this thread from the JVM when done.
        jvm->DetachCurrentThread();
    }  // end parallel region

    writingDone.store(true);
    queueCV.notify_all();

    // Wait for the I/O thread to finish writing all images.
    ioThread.join();


    // Optionally, you may destroy the JVM here if you no longer need it.
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

