// The bridge between the Java renderer and NVIDIA's NGX SDK, through which DLSS is reached. Kept
// to the few calls the renderer makes: extension queries before the Vulkan instance and device
// exist, initialisation, the optimal traced size for a quality mode, feature creation, one
// evaluate per frame and teardown. Vulkan handles cross as 64-bit integers.

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <vulkan/vulkan.h>
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_helpers_dlssd.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"

// NGX identifies applications without an NVIDIA-issued id by a GUID-like project id.
static const char PROJECT_ID[] = "5d2f0d0a-6b4e-4c1d-9a3f-7e8b1c2d3e4f";
static const char ENGINE_VERSION[] = "0.1.0";

static NVSDK_NGX_Parameter *params;
static NVSDK_NGX_Result lastResult = NVSDK_NGX_Result_Success;
static wchar_t appData[1024];
static wchar_t libraryDir[1024];
static const wchar_t *libraryDirs[1];
static NVSDK_NGX_FeatureCommonInfo common;

// Paths reach NGX as wide strings; ours are plain ASCII directories.
static void widen(JNIEnv *env, jstring s, wchar_t *out, size_t capacity)
{
	const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
	size_t i = 0;
	for (; utf[i] != 0 && i + 1 < capacity; ++i)
	{
		out[i] = (wchar_t) (unsigned char) utf[i];
	}
	out[i] = 0;
	(*env)->ReleaseStringUTFChars(env, s, utf);
}

static void prepareCommon(JNIEnv *env, jstring appDataPath, jstring libraryPath)
{
	widen(env, appDataPath, appData, sizeof appData / sizeof appData[0]);
	widen(env, libraryPath, libraryDir, sizeof libraryDir / sizeof libraryDir[0]);
	libraryDirs[0] = libraryDir;
	memset(&common, 0, sizeof common);
	common.PathListInfo.Path = libraryDirs;
	common.PathListInfo.Length = 1;
}

static NVSDK_NGX_FeatureDiscoveryInfo discovery(void)
{
	NVSDK_NGX_FeatureDiscoveryInfo info;
	memset(&info, 0, sizeof info);
	info.SDKVersion = NVSDK_NGX_Version_API;
	info.FeatureID = NVSDK_NGX_Feature_SuperSampling;
	info.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
	info.Identifier.v.ProjectDesc.ProjectId = PROJECT_ID;
	info.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
	info.Identifier.v.ProjectDesc.EngineVersion = ENGINE_VERSION;
	info.ApplicationDataPath = appData;
	info.FeatureInfo = &common;
	return info;
}

static jobjectArray extensionNames(JNIEnv *env, uint32_t count, const VkExtensionProperties *props)
{
	jclass stringClass = (*env)->FindClass(env, "java/lang/String");
	jobjectArray array = (*env)->NewObjectArray(env, (jsize) count, stringClass, NULL);
	for (uint32_t i = 0; i < count; ++i)
	{
		jstring name = (*env)->NewStringUTF(env, props[i].extensionName);
		(*env)->SetObjectArrayElement(env, array, (jsize) i, name);
		(*env)->DeleteLocalRef(env, name);
	}
	return array;
}

JNIEXPORT jobjectArray JNICALL Java_rltx_vk_Ngx_instanceExtensions(JNIEnv *env, jclass cls, jstring appDataPath, jstring libraryPath)
{
	prepareCommon(env, appDataPath, libraryPath);
	NVSDK_NGX_FeatureDiscoveryInfo info = discovery();
	uint32_t count = 0;
	VkExtensionProperties *props = NULL;
	lastResult = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&info, &count, &props);
	if (NVSDK_NGX_FAILED(lastResult))
	{
		return NULL;
	}
	return extensionNames(env, count, props);
}

JNIEXPORT jobjectArray JNICALL Java_rltx_vk_Ngx_deviceExtensions(JNIEnv *env, jclass cls, jlong instance, jlong physicalDevice, jstring appDataPath, jstring libraryPath)
{
	prepareCommon(env, appDataPath, libraryPath);
	NVSDK_NGX_FeatureDiscoveryInfo info = discovery();
	uint32_t count = 0;
	VkExtensionProperties *props = NULL;
	lastResult = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements((VkInstance) (uintptr_t) instance, (VkPhysicalDevice) (uintptr_t) physicalDevice, &info, &count, &props);
	if (NVSDK_NGX_FAILED(lastResult))
	{
		return NULL;
	}
	return extensionNames(env, count, props);
}

// Returns 1 when DLSS is ready, -2 when the driver is too old, -3 when the GPU lacks it, -4 when
// NGX denied it to this application, or the NGX result of the failed call.
JNIEXPORT jint JNICALL Java_rltx_vk_Ngx_init(JNIEnv *env, jclass cls, jlong instance, jlong physicalDevice, jlong device, jstring appDataPath, jstring libraryPath)
{
	prepareCommon(env, appDataPath, libraryPath);
	lastResult = NVSDK_NGX_VULKAN_Init_with_ProjectID(PROJECT_ID, NVSDK_NGX_ENGINE_TYPE_CUSTOM, ENGINE_VERSION, appData,
		(VkInstance) (uintptr_t) instance, (VkPhysicalDevice) (uintptr_t) physicalDevice, (VkDevice) (uintptr_t) device,
		NULL, NULL, &common, NVSDK_NGX_Version_API);
	if (NVSDK_NGX_FAILED(lastResult))
	{
		return (jint) lastResult;
	}
	lastResult = NVSDK_NGX_VULKAN_GetCapabilityParameters(&params);
	if (NVSDK_NGX_FAILED(lastResult))
	{
		params = NULL;
		return (jint) lastResult;
	}
	int needsDriver = 0;
	int available = 0;
	int initResult = 0;
	if (NVSDK_NGX_SUCCEED(NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDriver)) && needsDriver)
	{
		return -2;
	}
	if (NVSDK_NGX_FAILED(NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_SuperSampling_Available, &available)) || !available)
	{
		return -3;
	}
	if (NVSDK_NGX_SUCCEED(NVSDK_NGX_Parameter_GetI(params, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initResult))
		&& initResult != 0 && NVSDK_NGX_FAILED((NVSDK_NGX_Result) initResult))
	{
		lastResult = (NVSDK_NGX_Result) initResult;
		return -4;
	}
	return 1;
}

JNIEXPORT jintArray JNICALL Java_rltx_vk_Ngx_optimalSettings(JNIEnv *env, jclass cls, jint outWidth, jint outHeight, jint quality)
{
	if (params == NULL)
	{
		return NULL;
	}
	unsigned int renderWidth = 0, renderHeight = 0, maxWidth = 0, maxHeight = 0, minWidth = 0, minHeight = 0;
	float sharpness = 0.0f;
	lastResult = NGX_DLSS_GET_OPTIMAL_SETTINGS(params, (unsigned int) outWidth, (unsigned int) outHeight, (NVSDK_NGX_PerfQuality_Value) quality,
		&renderWidth, &renderHeight, &maxWidth, &maxHeight, &minWidth, &minHeight, &sharpness);
	if (NVSDK_NGX_FAILED(lastResult) || renderWidth == 0 || renderHeight == 0)
	{
		return NULL;
	}
	jintArray out = (*env)->NewIntArray(env, 2);
	jint sizes[2] = {(jint) renderWidth, (jint) renderHeight};
	(*env)->SetIntArrayRegion(env, out, 0, 2, sizes);
	return out;
}

// Records the feature's setup into the command buffer, which the caller submits and waits for.
JNIEXPORT jlong JNICALL Java_rltx_vk_Ngx_createFeature(JNIEnv *env, jclass cls, jlong device, jlong cmd, jint renderWidth, jint renderHeight, jint outWidth, jint outHeight, jint quality, jint flags)
{
	if (params == NULL)
	{
		return 0;
	}
	NVSDK_NGX_DLSS_Create_Params create;
	memset(&create, 0, sizeof create);
	create.Feature.InWidth = (unsigned int) renderWidth;
	create.Feature.InHeight = (unsigned int) renderHeight;
	create.Feature.InTargetWidth = (unsigned int) outWidth;
	create.Feature.InTargetHeight = (unsigned int) outHeight;
	create.Feature.InPerfQualityValue = (NVSDK_NGX_PerfQuality_Value) quality;
	create.InFeatureCreateFlags = (int) flags;
	create.InEnableOutputSubrects = false;
	NVSDK_NGX_Handle *handle = NULL;
	lastResult = NGX_VULKAN_CREATE_DLSS_EXT1((VkDevice) (uintptr_t) device, (VkCommandBuffer) (uintptr_t) cmd, 1, 1, &handle, params, &create);
	if (NVSDK_NGX_FAILED(lastResult))
	{
		return 0;
	}
	return (jlong) (uintptr_t) handle;
}

static NVSDK_NGX_Resource_VK resource(jlong view, jlong image, jint format, jint width, jint height, bool readWrite)
{
	VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
	return NVSDK_NGX_Create_ImageView_Resource_VK((VkImageView) (uintptr_t) view, (VkImage) (uintptr_t) image, range, (VkFormat) format,
		(unsigned int) width, (unsigned int) height, readWrite);
}

// Records the upscale into the frame's command buffer. Inputs are at the traced size and in the
// shader-read-only layout, the output at the view's size and in the general layout.
JNIEXPORT jint JNICALL Java_rltx_vk_Ngx_evaluate(JNIEnv *env, jclass cls, jlong cmd, jlong feature,
	jlong colorImage, jlong colorView, jint colorFormat, jint inWidth, jint inHeight,
	jlong outImage, jlong outView, jint outFormat, jint outWidth, jint outHeight,
	jlong depthImage, jlong depthView, jint depthFormat,
	jlong motionImage, jlong motionView, jint motionFormat,
	jlong biasImage, jlong biasView, jint biasFormat,
	jfloat jitterX, jfloat jitterY, jboolean reset)
{
	NVSDK_NGX_Resource_VK color = resource(colorView, colorImage, colorFormat, inWidth, inHeight, false);
	NVSDK_NGX_Resource_VK bias = resource(biasView, biasImage, biasFormat, inWidth, inHeight, false);
	NVSDK_NGX_Resource_VK output = resource(outView, outImage, outFormat, outWidth, outHeight, true);
	NVSDK_NGX_Resource_VK depth = resource(depthView, depthImage, depthFormat, inWidth, inHeight, false);
	NVSDK_NGX_Resource_VK motion = resource(motionView, motionImage, motionFormat, inWidth, inHeight, false);
	NVSDK_NGX_VK_DLSS_Eval_Params eval;
	memset(&eval, 0, sizeof eval);
	eval.Feature.pInColor = &color;
	eval.Feature.pInOutput = &output;
	eval.Feature.InSharpness = 0.0f;
	eval.pInDepth = &depth;
	eval.pInMotionVectors = &motion;
	eval.pInBiasCurrentColorMask = &bias;
	eval.InJitterOffsetX = jitterX;
	eval.InJitterOffsetY = jitterY;
	eval.InRenderSubrectDimensions.Width = (unsigned int) inWidth;
	eval.InRenderSubrectDimensions.Height = (unsigned int) inHeight;
	eval.InReset = reset ? 1 : 0;
	eval.InMVScaleX = 1.0f;
	eval.InMVScaleY = 1.0f;
	lastResult = NGX_VULKAN_EVALUATE_DLSS_EXT((VkCommandBuffer) (uintptr_t) cmd, (NVSDK_NGX_Handle *) (uintptr_t) feature, params, &eval);
	return (jint) lastResult;
}

// Ray Reconstruction as a denoiser at one size: input and output are both the traced size, the
// roughness rides in the normals' alpha and the depth is linear view depth.
JNIEXPORT jlong JNICALL Java_rltx_vk_Ngx_createDenoiser(JNIEnv *env, jclass cls, jlong device, jlong cmd, jint width, jint height, jint flags)
{
	if (params == NULL)
	{
		return 0;
	}
	NVSDK_NGX_DLSSD_Create_Params create;
	memset(&create, 0, sizeof create);
	create.InDenoiseMode = NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
	create.InRoughnessMode = NVSDK_NGX_DLSS_Roughness_Mode_Packed;
	create.InUseHWDepth = NVSDK_NGX_DLSS_Depth_Type_Linear;
	create.InWidth = (unsigned int) width;
	create.InHeight = (unsigned int) height;
	create.InTargetWidth = (unsigned int) width;
	create.InTargetHeight = (unsigned int) height;
	create.InPerfQualityValue = NVSDK_NGX_PerfQuality_Value_DLAA;
	create.InFeatureCreateFlags = (int) flags;
	create.InEnableOutputSubrects = false;
	NVSDK_NGX_Handle *handle = NULL;
	lastResult = NGX_VULKAN_CREATE_DLSSD_EXT1((VkDevice) (uintptr_t) device, (VkCommandBuffer) (uintptr_t) cmd, 1, 1, &handle, params, &create);
	if (NVSDK_NGX_FAILED(lastResult))
	{
		return 0;
	}
	return (jlong) (uintptr_t) handle;
}

JNIEXPORT jint JNICALL Java_rltx_vk_Ngx_evaluateDenoiser(JNIEnv *env, jclass cls, jlong cmd, jlong feature,
	jlong colorImage, jlong colorView, jint colorFormat, jint width, jint height,
	jlong albedoImage, jlong albedoView, jint albedoFormat,
	jlong specularImage, jlong specularView, jint specularFormat,
	jlong normalImage, jlong normalView, jint normalFormat,
	jlong depthImage, jlong depthView, jint depthFormat,
	jlong motionImage, jlong motionView, jint motionFormat,
	jlong outImage, jlong outView, jint outFormat,
	jlong biasImage, jlong biasView, jint biasFormat,
	jfloat jitterX, jfloat jitterY, jboolean reset)
{
	NVSDK_NGX_Resource_VK color = resource(colorView, colorImage, colorFormat, width, height, false);
	NVSDK_NGX_Resource_VK bias = resource(biasView, biasImage, biasFormat, width, height, false);
	NVSDK_NGX_Resource_VK albedo = resource(albedoView, albedoImage, albedoFormat, width, height, false);
	NVSDK_NGX_Resource_VK specular = resource(specularView, specularImage, specularFormat, width, height, false);
	NVSDK_NGX_Resource_VK normals = resource(normalView, normalImage, normalFormat, width, height, false);
	NVSDK_NGX_Resource_VK depth = resource(depthView, depthImage, depthFormat, width, height, false);
	NVSDK_NGX_Resource_VK motion = resource(motionView, motionImage, motionFormat, width, height, false);
	NVSDK_NGX_Resource_VK output = resource(outView, outImage, outFormat, width, height, true);
	NVSDK_NGX_VK_DLSSD_Eval_Params eval;
	memset(&eval, 0, sizeof eval);
	eval.pInDiffuseAlbedo = &albedo;
	eval.pInSpecularAlbedo = &specular;
	eval.pInNormals = &normals;
	eval.pInColor = &color;
	eval.pInOutput = &output;
	eval.pInDepth = &depth;
	eval.pInMotionVectors = &motion;
	eval.pInBiasCurrentColorMask = &bias;
	eval.InJitterOffsetX = jitterX;
	eval.InJitterOffsetY = jitterY;
	eval.InRenderSubrectDimensions.Width = (unsigned int) width;
	eval.InRenderSubrectDimensions.Height = (unsigned int) height;
	eval.InReset = reset ? 1 : 0;
	eval.InMVScaleX = 1.0f;
	eval.InMVScaleY = 1.0f;
	lastResult = NGX_VULKAN_EVALUATE_DLSSD_EXT((VkCommandBuffer) (uintptr_t) cmd, (NVSDK_NGX_Handle *) (uintptr_t) feature, params, &eval);
	return (jint) lastResult;
}

JNIEXPORT void JNICALL Java_rltx_vk_Ngx_releaseFeature(JNIEnv *env, jclass cls, jlong feature)
{
	NVSDK_NGX_VULKAN_ReleaseFeature((NVSDK_NGX_Handle *) (uintptr_t) feature);
}

JNIEXPORT void JNICALL Java_rltx_vk_Ngx_shutdown(JNIEnv *env, jclass cls, jlong device)
{
	if (params != NULL)
	{
		NVSDK_NGX_VULKAN_DestroyParameters(params);
		params = NULL;
	}
	NVSDK_NGX_VULKAN_Shutdown1((VkDevice) (uintptr_t) device);
}

JNIEXPORT jint JNICALL Java_rltx_vk_Ngx_lastResult(JNIEnv *env, jclass cls)
{
	return (jint) lastResult;
}
