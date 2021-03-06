/*
 * Copyright 2016 Hans Chen
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
package site.hanschen.easyloader.cache;

import site.hanschen.easyloader.cache.diskcache.DiskLruCache;
import site.hanschen.easyloader.log.EasyLoaderLog;

import java.io.File;
import java.io.IOException;

public class LruDiskCache<V> implements CacheManager<String, V> {

    private static final String TAG = "LruDiskCache";

    private static final int                  VALUE_COUNT = 1;
    private final        DiskCacheWriteLocker writeLocker = new DiskCacheWriteLocker();

    private final File             directory;
    private final long             maxSize;
    private final int              appVersion;
    private       DiskLruCache     diskLruCache;
    private       FileConverter<V> converter;

    public interface FileConverter<V> {

        V readFrom(File file);

        boolean writeTo(V value, File to);
    }

    public LruDiskCache(File directory, long maxSize, int appVersion, FileConverter<V> converter) {
        if (directory == null || maxSize <= 0 || appVersion <= 0 || converter == null) {
            throw new IllegalArgumentException("directory == null || maxSize <= 0 || appVersion <= 0 || converter == null");
        }
        this.directory = directory;
        this.maxSize = maxSize;
        this.appVersion = appVersion;
        this.converter = converter;
    }

    private synchronized DiskLruCache getDiskCache() throws IOException {
        if (diskLruCache == null) {
            diskLruCache = DiskLruCache.open(directory, appVersion, VALUE_COUNT, maxSize);
        }
        return diskLruCache;
    }

    @Override
    public V get(String key) {
        try {
            final DiskLruCache.Value value = getDiskCache().get(key);
            if (value != null) {
                return converter.readFrom(value.getFile(0));
            }
        } catch (IOException e) {
            EasyLoaderLog.w(TAG, "Unable to get from disk cache");
        }
        return null;
    }

    @Override
    public void put(String key, V value) {
        writeLocker.acquire(key);
        try {
            try {
                DiskLruCache diskCache = getDiskCache();
                DiskLruCache.Value current = diskCache.get(key);
                if (current != null) {
                    return;
                }

                DiskLruCache.Editor editor = diskCache.edit(key);
                if (editor == null) {
                    throw new IllegalStateException("Had two simultaneous puts for: " + key);
                }
                try {
                    File file = editor.getFile(0);
                    if (converter.writeTo(value, file)) {
                        editor.commit();
                    }
                } finally {
                    editor.abortUnlessCommitted();
                }
            } catch (IOException e) {
                EasyLoaderLog.w(TAG, "Unable to put to disk cache" + e);
            }
        } finally {
            writeLocker.release(key);
        }
    }

    @Override
    public V remove(String key) {
        try {
            getDiskCache().remove(key);
        } catch (IOException e) {
            EasyLoaderLog.w(TAG, "Unable to delete from disk cache: " + e);
        }

        return null;
    }

    @Override
    public long size() {
        try {
            return getDiskCache().size();
        } catch (IOException e) {
            EasyLoaderLog.w(TAG, "Unable to clear disk cache: " + e);
        }

        return 0;
    }

    @Override
    public void resize(long maxSize) {
        try {
            getDiskCache().setMaxSize(maxSize);
        } catch (IOException e) {
            EasyLoaderLog.w(TAG, "Unable to resize disk cache: " + e);
        }
    }

    @Override
    public long maxSize() {
        try {
            return getDiskCache().getMaxSize();
        } catch (IOException e) {
            EasyLoaderLog.w(TAG, "Unable to get max size: " + e);
        }

        return 0;
    }

    @Override
    public void clear() {
        try {
            getDiskCache().delete();
            resetDiskCache();
        } catch (IOException e) {
            EasyLoaderLog.w(TAG, "Unable to clear disk cache" + e);
        }
    }

    private synchronized void resetDiskCache() {
        diskLruCache = null;
    }
}
