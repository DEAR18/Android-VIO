#ifndef IMUPROCESSOR_H
#define IMUPROCESSOR_H

#include <condition_variable>
#include <jni.h>
#include <mutex>
#include <queue>
#include <string>
#include <thread>

struct ImuData {
  long timestamp; // nanoseconds
  float accX, accY, accZ;
  float gyroX, gyroY, gyroZ;
};

struct ProcessedImuData {
  long timestamp;
  float processedX, processedY, processedZ;
};

class ImuProcessor {
public:
  ImuProcessor();

  ~ImuProcessor();

  void startProcessing();

  void stopProcessing();

  void receiveImuData(long timestamp, float accX, float accY, float accZ,
                      float gyroX, float gyroY, float gyroZ);

  void setOutputCallback(JNIEnv *env, jobject callback_obj);

private:
  std::queue<ImuData> inputQueue;
  std::mutex queueMutex;
  std::condition_variable dataCondition;

  std::thread processingThread;
  bool running;

  void processImuData();

  JavaVM *javaVm;
  jobject javaCallbackObj;
  jmethodID onProcessedImuDataMethodId;

  void findJavaCallbackMethod(JNIEnv *env);
};

#endif // IMUPROCESSOR_H
