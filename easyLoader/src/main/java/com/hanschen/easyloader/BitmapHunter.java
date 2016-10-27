/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hanschen.easyloader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.NetworkInfo;

import com.hanschen.easyloader.action.Action;
import com.hanschen.easyloader.cache.CacheManager;
import com.hanschen.easyloader.downloader.ResponseException;
import com.hanschen.easyloader.request.Request;
import com.hanschen.easyloader.request.RequestHandler;
import com.hanschen.easyloader.request.Result;
import com.hanschen.easyloader.util.BitmapUtils;
import com.hanschen.easyloader.util.CloseUtils;
import com.hanschen.easyloader.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
import static android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL;
import static android.media.ExifInterface.ORIENTATION_ROTATE_180;
import static android.media.ExifInterface.ORIENTATION_ROTATE_270;
import static android.media.ExifInterface.ORIENTATION_ROTATE_90;
import static android.media.ExifInterface.ORIENTATION_TRANSPOSE;
import static android.media.ExifInterface.ORIENTATION_TRANSVERSE;
import static com.hanschen.easyloader.DiskPolicy.shouldReadFromDiskCache;
import static com.hanschen.easyloader.MemoryPolicy.shouldReadFromMemoryCache;


public class BitmapHunter implements Runnable {
    /**
     * Global lock for bitmap decoding to ensure that we are only are decoding one at a time. Since
     * this will only ever happen in background threads we help avoid excessive memory thrashing as
     * well as potential OOMs. Shamelessly stolen from Volley.
     */
    private static final Object DECODE_LOCK = new Object();

    private static final ThreadLocal<StringBuilder> NAME_BUILDER = new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
            return new StringBuilder(Utils.THREAD_PREFIX);
        }
    };

    private static final AtomicInteger SEQUENCE_GENERATOR = new AtomicInteger();

    private final EasyLoader                   loader;
    private final Dispatcher                   dispatcher;
    private final int                          sequence;
    private final CacheManager<String, Bitmap> memoryCache;
    private final CacheManager<String, Bitmap> diskCache;
    private final String                       key;
    private final Request                      data;
    private final int                          memoryPolicy;
    private final int                          diskPolicy;
    private final RequestHandler               requestHandler;
    /**
     * 相同请求会放入这个列表
     */
    private       Action                       action;
    private       List<Action>                 actions;
    private       Bitmap                       result;
    private       Future<?>                    future;
    private       LoadedFrom                   loadedFrom;
    private       Exception                    exception;
    private       int                          exifOrientation; // Determined during decoding of original resource.
    private       int                          retryCount;
    private       Priority                     priority;

    private BitmapHunter(EasyLoader loader,
                         Dispatcher dispatcher,
                         CacheManager<String, Bitmap> memoryCache,
                         CacheManager<String, Bitmap> diskCache,
                         Action action,
                         RequestHandler requestHandler) {
        this.sequence = SEQUENCE_GENERATOR.incrementAndGet();
        this.loader = loader;
        this.dispatcher = dispatcher;
        this.memoryCache = memoryCache;
        this.diskCache = diskCache;
        this.action = action;
        this.key = action.getKey();
        this.data = action.getRequest();
        this.priority = action.getPriority();
        this.memoryPolicy = action.getMemoryPolicy();
        this.diskPolicy = action.getDiskPolicy();
        this.requestHandler = requestHandler;
        this.retryCount = requestHandler.getRetryCount();
    }

    public static BitmapHunter forRequest(EasyLoader loader,
                                          Dispatcher dispatcher,
                                          CacheManager<String, Bitmap> memoryCache,
                                          CacheManager<String, Bitmap> diskCache,
                                          Action action) {
        Request request = action.getRequest();
        List<RequestHandler> requestHandlers = loader.getRequestHandlers();

        for (int i = 0, count = requestHandlers.size(); i < count; i++) {
            RequestHandler handler = requestHandlers.get(i);
            if (handler.canHandleRequest(request)) {
                return new BitmapHunter(loader, dispatcher, memoryCache, diskCache, action, handler);
            }
        }

        throw new IllegalStateException("Unrecognized type of request: " + request);
    }

    /**
     * Decode a byte stream into a Bitmap. This method will take into account additional information
     * about the supplied request in order to do the decoding efficiently (such as through leveraging
     * {@code inSampleSize}).
     */
    private Bitmap decodeStream(InputStream stream, Request request) throws IOException {
        MarkableInputStream markStream = new MarkableInputStream(stream);
        stream = markStream;
        markStream.allowMarksToExpire(false);
        long mark = markStream.savePosition(1024);

        final BitmapFactory.Options options = BitmapUtils.createBitmapOptions(request);
        final boolean calculateSize = BitmapUtils.requiresInSampleSize(options);

        boolean isWebPFile = Utils.isWebPFile(stream);
        boolean isPurgeable = request.purgeable && android.os.Build.VERSION.SDK_INT < 21;
        markStream.reset(mark);
        // We decode from a byte array because, a) when decoding a WebP network stream, BitmapFactory
        // throws a JNI Exception, so we workaround by decoding a byte array, or b) user requested
        // purgeable, which only affects bitmaps decoded from byte arrays.
        if (isWebPFile || isPurgeable) {
            byte[] bytes = Utils.toByteArray(stream);
            if (calculateSize) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                BitmapUtils.calculateInSampleSize(request.targetWidth, request.targetHeight, options, request);
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } else {
            if (calculateSize) {
                BitmapFactory.decodeStream(stream, null, options);
                BitmapUtils.calculateInSampleSize(request.targetWidth, request.targetHeight, options, request);
                markStream.reset(mark);
            }
            markStream.allowMarksToExpire(true);
            Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
            if (bitmap == null) {
                // Treat null as an IO exception, we will eventually retry.
                throw new IOException("Failed to decode stream.");
            }
            return bitmap;
        }
    }

    @Override
    public void run() {

        updateThreadName(data);
        try {
            result = hunt();
            if (result == null) {
                dispatcher.dispatchFailed(this);
            } else {
                dispatcher.dispatchSuccess(this);
            }
        } catch (ResponseException e) {
            if (e.getResponseCode() != 504) {
                exception = e;
            }
            dispatcher.dispatchFailed(this);
        } catch (IOException e) {
            exception = e;
            dispatcher.dispatchRetry(this);
        } catch (OutOfMemoryError e) {
            StringWriter writer = new StringWriter();
            exception = new RuntimeException(writer.toString(), e);
            dispatcher.dispatchFailed(this);
        } catch (Exception e) {
            exception = e;
            dispatcher.dispatchFailed(this);
        } finally {
            Thread.currentThread().setName(Utils.THREAD_IDLE_NAME);
        }
    }

    private static void updateThreadName(Request request) {

        String name = request.getName();
        StringBuilder builder = NAME_BUILDER.get();
        builder.ensureCapacity(Utils.THREAD_PREFIX.length() + name.length());
        builder.replace(Utils.THREAD_PREFIX.length(), builder.length(), name);

        Thread.currentThread().setName(builder.toString());
    }

    /**
     * 抓取Bitmap并对其进行处理
     *
     * @return 处理完成后的Bitmap
     * @throws IOException
     */
    public Bitmap hunt() throws IOException {

        Bitmap bitmap = null;

        if (shouldReadFromMemoryCache(memoryPolicy)) {
            bitmap = memoryCache.get(key);
            if (bitmap != null) {
                loadedFrom = LoadedFrom.MEMORY;
                return bitmap;
            }
        }

        if (shouldReadFromDiskCache(diskPolicy)) {
            bitmap = diskCache.get(key);
            if (bitmap != null) {
                loadedFrom = LoadedFrom.DISK;
                return bitmap;
            }
        }

        Result result = requestHandler.handle(data);
        if (result != null) {

            loadedFrom = result.getLoadedFrom();
            exifOrientation = result.getExifOrientation();
            bitmap = result.getBitmap();
            // 图片可能保存在InputStream而不是bitmap，比如NetworkRequestHandler
            if (bitmap == null) {
                InputStream is = result.getStream();
                try {
                    bitmap = decodeStream(is, data);
                } finally {
                    CloseUtils.close(is);
                }
            }
        }

        if (bitmap != null) {
            if (data.needsTransformation() || exifOrientation != 0) {
                synchronized (DECODE_LOCK) {
                    if (data.needsMatrixTransform() || exifOrientation != 0) {
                        bitmap = transformResult(data, bitmap, exifOrientation);
                    }
                    if (data.hasCustomTransformations()) {
                        bitmap = applyCustomTransformations(data.transformations, bitmap);
                    }
                }
            }
        }

        return bitmap;
    }

    private static Bitmap applyCustomTransformations(List<Transformation> transformations, Bitmap result) {
        for (int i = 0, count = transformations.size(); i < count; i++) {
            final Transformation transformation = transformations.get(i);
            Bitmap newResult;
            try {
                newResult = transformation.transform(result);
            } catch (final RuntimeException e) {
                EasyLoader.HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        throw new RuntimeException("Transformation " + transformation.key() + " crashed with exception.", e);
                    }
                });
                return null;
            }

            if (newResult == null) {
                final StringBuilder builder = new StringBuilder().append("Transformation ")
                                                                 .append(transformation.key())
                                                                 .append(" returned null after ")
                                                                 .append(i)
                                                                 .append(" previous transformation(s).\n\nTransformation list:\n");
                for (Transformation t : transformations) {
                    builder.append(t.key()).append('\n');
                }
                EasyLoader.HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        throw new NullPointerException(builder.toString());
                    }
                });
                return null;
            }

            if (newResult == result && result.isRecycled()) {
                EasyLoader.HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        throw new IllegalStateException("Transformation " + transformation.key() + " returned input Bitmap but recycled it.");
                    }
                });
                return null;
            }

            // If the transformation returned a new bitmap ensure they recycled the original.
            if (newResult != result && !result.isRecycled()) {
                EasyLoader.HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        throw new IllegalStateException("Transformation " + transformation.key() + " mutated input Bitmap but failed to recycle the original.");
                    }
                });
                return null;
            }

            result = newResult;
        }
        return result;
    }

    private static Bitmap transformResult(Request data, Bitmap result, int exifOrientation) {
        int inWidth = result.getWidth();
        int inHeight = result.getHeight();
        boolean onlyScaleDown = data.onlyScaleDown;

        int drawX = 0;
        int drawY = 0;
        int drawWidth = inWidth;
        int drawHeight = inHeight;

        Matrix matrix = new Matrix();

        if (data.needsMatrixTransform() || exifOrientation != 0) {
            int targetWidth = data.targetWidth;
            int targetHeight = data.targetHeight;

            float targetRotation = data.rotationDegrees;
            if (targetRotation != 0) {
                double cosR = Math.cos(Math.toRadians(targetRotation));
                double sinR = Math.sin(Math.toRadians(targetRotation));
                if (data.hasRotationPivot) {
                    matrix.setRotate(targetRotation, data.rotationPivotX, data.rotationPivotY);
                    // Recalculate dimensions after rotation around pivot point
                    double x1T = data.rotationPivotX * (1.0 - cosR) + (data.rotationPivotY * sinR);
                    double y1T = data.rotationPivotY * (1.0 - cosR) - (data.rotationPivotX * sinR);
                    double x2T = x1T + (data.targetWidth * cosR);
                    double y2T = y1T + (data.targetWidth * sinR);
                    double x3T = x1T + (data.targetWidth * cosR) - (data.targetHeight * sinR);
                    double y3T = y1T + (data.targetWidth * sinR) + (data.targetHeight * cosR);
                    double x4T = x1T - (data.targetHeight * sinR);
                    double y4T = y1T + (data.targetHeight * cosR);

                    double maxX = Math.max(x4T, Math.max(x3T, Math.max(x1T, x2T)));
                    double minX = Math.min(x4T, Math.min(x3T, Math.min(x1T, x2T)));
                    double maxY = Math.max(y4T, Math.max(y3T, Math.max(y1T, y2T)));
                    double minY = Math.min(y4T, Math.min(y3T, Math.min(y1T, y2T)));
                    targetWidth = (int) Math.floor(maxX - minX);
                    targetHeight = (int) Math.floor(maxY - minY);
                } else {
                    matrix.setRotate(targetRotation);
                    // Recalculate dimensions after rotation (around origin)
                    double x1T = 0.0;
                    double y1T = 0.0;
                    double x2T = (data.targetWidth * cosR);
                    double y2T = (data.targetWidth * sinR);
                    double x3T = (data.targetWidth * cosR) - (data.targetHeight * sinR);
                    double y3T = (data.targetWidth * sinR) + (data.targetHeight * cosR);
                    double x4T = -(data.targetHeight * sinR);
                    double y4T = (data.targetHeight * cosR);

                    double maxX = Math.max(x4T, Math.max(x3T, Math.max(x1T, x2T)));
                    double minX = Math.min(x4T, Math.min(x3T, Math.min(x1T, x2T)));
                    double maxY = Math.max(y4T, Math.max(y3T, Math.max(y1T, y2T)));
                    double minY = Math.min(y4T, Math.min(y3T, Math.min(y1T, y2T)));
                    targetWidth = (int) Math.floor(maxX - minX);
                    targetHeight = (int) Math.floor(maxY - minY);
                }
            }

            // EXIf interpretation should be done before cropping in case the dimensions need to
            // be recalculated
            if (exifOrientation != 0) {
                int exifRotation = getExifRotation(exifOrientation);
                int exifTranslation = getExifTranslation(exifOrientation);
                if (exifRotation != 0) {
                    matrix.preRotate(exifRotation);
                    if (exifRotation == 90 || exifRotation == 270) {
                        // Recalculate dimensions after exif rotation
                        int tmpHeight = targetHeight;
                        targetHeight = targetWidth;
                        targetWidth = tmpHeight;
                    }
                }
                if (exifTranslation != 1) {
                    matrix.postScale(exifTranslation, 1);
                }
            }

            if (data.centerCrop) {
                // Keep aspect ratio if one dimension is set to 0
                float widthRatio = targetWidth != 0 ? targetWidth / (float) inWidth : targetHeight / (float) inHeight;
                float heightRatio = targetHeight != 0 ? targetHeight / (float) inHeight : targetWidth / (float) inWidth;
                float scaleX, scaleY;
                if (widthRatio > heightRatio) {
                    int newSize = (int) Math.ceil(inHeight * (heightRatio / widthRatio));
                    drawY = (inHeight - newSize) / 2;
                    drawHeight = newSize;
                    scaleX = widthRatio;
                    scaleY = targetHeight / (float) drawHeight;
                } else if (widthRatio < heightRatio) {
                    int newSize = (int) Math.ceil(inWidth * (widthRatio / heightRatio));
                    drawX = (inWidth - newSize) / 2;
                    drawWidth = newSize;
                    scaleX = targetWidth / (float) drawWidth;
                    scaleY = heightRatio;
                } else {
                    drawX = 0;
                    drawWidth = inWidth;
                    scaleX = scaleY = heightRatio;
                }
                if (shouldResize(onlyScaleDown, inWidth, inHeight, targetWidth, targetHeight)) {
                    matrix.preScale(scaleX, scaleY);
                }
            } else if (data.centerInside) {
                // Keep aspect ratio if one dimension is set to 0
                float widthRatio = targetWidth != 0 ? targetWidth / (float) inWidth : targetHeight / (float) inHeight;
                float heightRatio = targetHeight != 0 ? targetHeight / (float) inHeight : targetWidth / (float) inWidth;
                float scale = widthRatio < heightRatio ? widthRatio : heightRatio;
                if (shouldResize(onlyScaleDown, inWidth, inHeight, targetWidth, targetHeight)) {
                    matrix.preScale(scale, scale);
                }
            } else if ((targetWidth != 0 || targetHeight != 0) //
                    && (targetWidth != inWidth || targetHeight != inHeight)) {
                // If an explicit target size has been specified and they do not match the results bounds,
                // pre-scale the existing matrix appropriately.
                // Keep aspect ratio if one dimension is set to 0.
                float sx = targetWidth != 0 ? targetWidth / (float) inWidth : targetHeight / (float) inHeight;
                float sy = targetHeight != 0 ? targetHeight / (float) inHeight : targetWidth / (float) inWidth;
                if (shouldResize(onlyScaleDown, inWidth, inHeight, targetWidth, targetHeight)) {
                    matrix.preScale(sx, sy);
                }
            }
        }

        Bitmap newResult = Bitmap.createBitmap(result, drawX, drawY, drawWidth, drawHeight, matrix, true);
        if (newResult != result) {
            result.recycle();
            result = newResult;
        }

        return result;
    }

    private static boolean shouldResize(boolean onlyScaleDown, int inWidth, int inHeight, int targetWidth, int targetHeight) {
        return !onlyScaleDown || inWidth > targetWidth || inHeight > targetHeight;
    }

    private static int getExifRotation(int orientation) {
        int rotation;
        switch (orientation) {
            case ORIENTATION_ROTATE_90:
            case ORIENTATION_TRANSPOSE:
                rotation = 90;
                break;
            case ORIENTATION_ROTATE_180:
            case ORIENTATION_FLIP_VERTICAL:
                rotation = 180;
                break;
            case ORIENTATION_ROTATE_270:
            case ORIENTATION_TRANSVERSE:
                rotation = 270;
                break;
            default:
                rotation = 0;
        }
        return rotation;
    }

    private static int getExifTranslation(int orientation) {
        int translation;
        switch (orientation) {
            case ORIENTATION_FLIP_HORIZONTAL:
            case ORIENTATION_FLIP_VERTICAL:
            case ORIENTATION_TRANSPOSE:
            case ORIENTATION_TRANSVERSE:
                translation = -1;
                break;
            default:
                translation = 1;
        }
        return translation;
    }

    /**
     * 相同URL请求，会通过attach方法把后来的请求放到已有请求中去
     */
    void attach(Action action) {
        if (this.action == null) {
            this.action = action;
            return;
        }

        if (actions == null) {
            actions = new ArrayList<>(3);
        }

        actions.add(action);

        //若新attach的请求优先级比较高，则提高优先级
        Priority actionPriority = action.getPriority();
        if (actionPriority.ordinal() > priority.ordinal()) {
            priority = actionPriority;
        }
    }

    /**
     * 把action从该hunter中解绑
     *
     * @param action 需要解绑的action
     */
    void detach(Action action) {
        boolean detached = false;
        if (this.action == action) {
            this.action = null;
            detached = true;
        } else if (actions != null) {
            detached = actions.remove(action);
        }

        //重新计算优先级
        if (detached/* && action.getPriority() == priority*/) {
            priority = computeNewPriority();
        }
    }

    /**
     * 重新计算优先级，遍历action以及attach到这个Hunter的action，以最高优先级为最新优先级
     */
    private Priority computeNewPriority() {

        Priority newPriority = Priority.LOW;
        boolean hasMultiple = actions != null && !actions.isEmpty();
        boolean hasAny = action != null || hasMultiple;

        if (!hasAny) {
            return newPriority;
        }

        if (action != null) {
            newPriority = action.getPriority();
        }

        if (hasMultiple) {
            for (int i = 0, n = actions.size(); i < n; i++) {
                Priority actionPriority = actions.get(i).getPriority();
                if (actionPriority.ordinal() > newPriority.ordinal()) {
                    newPriority = actionPriority;
                }
            }
        }

        return newPriority;
    }

    /**
     * 若action为空且没有其他action绑定到这个这个hunter，就尝试执行{@link Future#cancel(boolean)}
     *
     * @return future是否取消成功
     */
    boolean cancel() {
        return action == null && (actions == null || actions.isEmpty()) && future != null && future.cancel(false);
    }

    boolean isCancelled() {
        return future != null && future.isCancelled();
    }

    /**
     * 根据网络状态以及已重试次数，返回是否需要继续尝试发起请求
     *
     * @param airplaneMode 飞行模式
     * @param info         网络状态
     * @return 是否重试
     */
    boolean shouldRetry(boolean airplaneMode, NetworkInfo info) {
        if (retryCount <= 0) {
            return false;
        }
        retryCount--;
        return requestHandler.shouldRetry(airplaneMode, info);
    }

    boolean supportsReplay() {
        return requestHandler.supportsReplay();
    }

    void setFuture(Future<?> future) {
        this.future = future;
    }

    Bitmap getResult() {
        return result;
    }

    String getKey() {
        return key;
    }

    int getMemoryPolicy() {
        return memoryPolicy;
    }

    int getDiskPolicy() {
        return diskPolicy;
    }

    Request getData() {
        return data;
    }

    public Action getAction() {
        return action;
    }

    EasyLoader getLoader() {
        return loader;
    }

    List<Action> getActions() {
        return actions;
    }

    Exception getException() {
        return exception;
    }

    LoadedFrom getLoadedFrom() {
        return loadedFrom;
    }

    Priority getPriority() {
        return priority;
    }

    int getSequence() {
        return sequence;
    }
}
