#include <jni.h>
#include <android/log.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

typedef unsigned char uint8;
typedef unsigned short uint16;
typedef unsigned int uint32;


extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_MobileSLAM_RosCameraCapture_CameraUtil_convertYUVToARGB(JNIEnv *env, jclass clazz,
                                                                jbyteArray y_data,
                                                                jbyteArray u_data,
                                                                jbyteArray v_data,
                                                                jint y_row_stride,
                                                                jint uv_row_stride,
                                                                jint uv_pixel_stride, jint width,
                                                                jint height) {

    jboolean inputCopy = JNI_FALSE;
    jbyte* const y_buff = env->GetByteArrayElements(y_data, &inputCopy);
    uint8* const yData = reinterpret_cast<uint8*>(y_buff);
    jbyte* const u_buff = env->GetByteArrayElements(u_data, &inputCopy);
    uint8* const uData = reinterpret_cast<uint8*>(u_buff);
    jbyte* const v_buff = env->GetByteArrayElements(v_data, &inputCopy);
    uint8* const vData = reinterpret_cast<uint8*>(v_buff);

    jbyteArray returnedArray = env->NewByteArray(width*height*4);
    jbyte *outData = env->GetByteArrayElements(returnedArray, NULL);

    static const int kMaxChannelValue = 262143;

    int index = 0;

    for(int y=0; y<height; y++){
        const uint8* pY = yData + y_row_stride * y;

        const int uv_row_start = uv_row_stride * (y >> 1);
        const uint8* pU = uData + uv_row_start;
        const uint8* pV = vData + uv_row_start;

        for(int x=0; x<width; x++){

            const int uv_offset = (x >> 1) * uv_pixel_stride;

            int nY = pY[x];
            int nU = pU[uv_offset];
            int nV = pV[uv_offset];

            nY -= 16;
            nU -= 128;
            nV -= 128;
            if(nY < 0){
                nY = 0;
            }

            int nR = (int)(1192 * nY + 1634 * nV);
            int nG = (int)(1192 * nY - 833 * nV - 400 * nU);
            int nB = (int)(1192 * nY + 2066 * nU);

            nR = MIN(kMaxChannelValue, MAX(0, nR));
            nG = MIN(kMaxChannelValue, MAX(0, nG));
            nB = MIN(kMaxChannelValue, MAX(0, nB));

            nR = (nR >> 10) & 0xff;
            nG = (nG >> 10) & 0xff;
            nB = (nB >> 10) & 0xff;

            outData[index++] = nR & 0x000000ff;
            outData[index++] = nG;
            outData[index++] = nB;
            outData[index++] = 0xff;
        }
    }

    env->ReleaseByteArrayElements(u_data, u_buff, JNI_ABORT);
    env->ReleaseByteArrayElements(v_data, v_buff, JNI_ABORT);
    env->ReleaseByteArrayElements(y_data, y_buff, JNI_ABORT);
    env->ReleaseByteArrayElements(returnedArray, outData, JNI_ABORT);

    return returnedArray;
}


extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_MobileSLAM_RosCameraCapture_CameraUtil_convertYUVToARGB_1opencv(JNIEnv *env, jclass clazz,
                                                                         jbyteArray y_data,
                                                                         jbyteArray u_data,
                                                                         jbyteArray v_data,
                                                                         jint y_row_stride,
                                                                         jint uv_row_stride,
                                                                         jint uv_pixel_stride,
                                                                         jint width, jint height) {
    // TODO: implement convertYUVToARGB_opencv()
    jboolean inputCopy = JNI_FALSE;
    jbyte* const y_buff = env->GetByteArrayElements(y_data, &inputCopy);
    uint8* const yData = reinterpret_cast<uint8*>(y_buff);
    jbyte* const u_buff = env->GetByteArrayElements(u_data, &inputCopy);
    uint8* const uData = reinterpret_cast<uint8*>(u_buff);
    jbyte* const v_buff = env->GetByteArrayElements(v_data, &inputCopy);
    uint8* const vData = reinterpret_cast<uint8*>(v_buff);

    jbyteArray returnedArray = env->NewByteArray(width*height);
    jbyte *outData = env->GetByteArrayElements(returnedArray, NULL);

    cv::Size actual_size(width, height);
    cv::Size half_size(width/2, height/2);

    cv::Mat y(actual_size, CV_8UC1, yData);
    cv::Mat u(half_size, CV_8UC1, uData);
    cv::Mat v(half_size, CV_8UC1, vData);

    cv::Mat u_resized, v_resized;
    cv::resize(u, u_resized, actual_size, 0, 0, cv::INTER_NEAREST);
    cv::resize(v, v_resized, actual_size, 0, 0, cv::INTER_NEAREST);

    cv::Mat yuv;
    std::vector<cv::Mat> yuv_channels = { y, u_resized, v_resized };
    cv::merge(yuv_channels, yuv);

    cv::Mat bgr;
    cv::cvtColor(yuv, bgr, cv::COLOR_YUV2BGR);

    uint8* bgr_data = bgr.data;
    for(int y=0; y<bgr.rows; y++){
        for(int x=0; x<bgr.cols; x++){
            int index = y * width + x;
            cv::Vec3b pixel = bgr.at<cv::Vec3b>(y,x);
            uint32_t B = pixel[0];
            uint32_t G = pixel[1];
            uint32_t R = pixel[2];
            if(bgr_data[index] == 0){
                outData[index] = 0xFF000000;
            }else{
                outData[index] = 0xFF000000 | (R << 16) | (G << 8) | B;
            }
        }
    }

    env->ReleaseByteArrayElements(u_data, u_buff, JNI_ABORT);
    env->ReleaseByteArrayElements(v_data, v_buff, JNI_ABORT);
    env->ReleaseByteArrayElements(y_data, y_buff, JNI_ABORT);
    env->ReleaseByteArrayElements(returnedArray, outData, JNI_ABORT);

    return returnedArray;

}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_MobileSLAM_RosCameraCapture_CameraUtil_convertYUVToARGBUint32(JNIEnv *env, jclass clazz,
                                                                       jbyteArray y_data,
                                                                       jbyteArray u_data,
                                                                       jbyteArray v_data,
                                                                       jint y_row_stride,
                                                                       jint uv_row_stride,
                                                                       jint uv_pixel_stride,
                                                                       jint width, jint height) {

    jboolean inputCopy = JNI_FALSE;
    jbyte* const y_buff = env->GetByteArrayElements(y_data, &inputCopy);
    uint8* const yData = reinterpret_cast<uint8*>(y_buff);
    jbyte* const u_buff = env->GetByteArrayElements(u_data, &inputCopy);
    uint8* const uData = reinterpret_cast<uint8*>(u_buff);
    jbyte* const v_buff = env->GetByteArrayElements(v_data, &inputCopy);
    uint8* const vData = reinterpret_cast<uint8*>(v_buff);

    jintArray returnedArray = env->NewIntArray(width * height);
    jint *outData = env->GetIntArrayElements(returnedArray, NULL);

    static const int kMaxChannelValue = 262143;

    int index = 0;

    for(int y=0; y<height; y++){
        const uint8* pY = yData + y_row_stride * y;

        const int uv_row_start = uv_row_stride * (y >> 1);
        const uint8* pU = uData + uv_row_start;
        const uint8* pV = vData + uv_row_start;

        for(int x=0; x<width; x++){

            const int uv_offset = (x >> 1) * uv_pixel_stride;

            int nY = pY[x];
            int nU = pU[uv_offset];
            int nV = pV[uv_offset];

            nY -= 16;
            nU -= 128;
            nV -= 128;
            if(nY < 0){
                nY = 0;
            }

            int nR = (int)(1192 * nY + 1634 * nV);
            int nG = (int)(1192 * nY - 833 * nV - 400 * nU);
            int nB = (int)(1192 * nY + 2066 * nU);

            nR = MIN(kMaxChannelValue, MAX(0, nR));
            nG = MIN(kMaxChannelValue, MAX(0, nG));
            nB = MIN(kMaxChannelValue, MAX(0, nB));

            nR = (nR >> 10) & 0xff;
            nG = (nG >> 10) & 0xff;
            nB = (nB >> 10) & 0xff;

            outData[index++] = 0xff000000 | nR << 16 | nG << 8 | nB;
        }
    }

    env->ReleaseByteArrayElements(u_data, u_buff, JNI_ABORT);
    env->ReleaseByteArrayElements(v_data, v_buff, JNI_ABORT);
    env->ReleaseByteArrayElements(y_data, y_buff, JNI_ABORT);
    env->ReleaseIntArrayElements(returnedArray, outData, JNI_ABORT);

    return returnedArray;
}