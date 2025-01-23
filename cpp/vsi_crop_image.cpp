#include <opencv2/opencv.hpp>
#include <opencv2/gpu/gpu.hpp>

bool is_mostly_white(const cv::Mat& img, int threshold = 230, double white_ratio = 0.5)
{
    cv::Mat gray;
    cv::cvtColor(img, gray, CV_BGR2GRAY);
    cv::Mat mask = gray > threshold;
    int white = cv::countNonZero(mask);
    return white > white_ratio * img.total();
}

bool is_mostly_green_or_blue(const cv::Mat& img,) 