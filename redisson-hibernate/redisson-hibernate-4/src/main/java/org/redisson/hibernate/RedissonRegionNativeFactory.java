/**
 * Copyright (c) 2013-2024 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.hibernate;

import org.hibernate.cache.CacheException;
import org.hibernate.cfg.Settings;
import org.redisson.MapCacheNativeWrapper;
import org.redisson.Redisson;
import org.redisson.api.RMapCache;
import org.redisson.api.RMapCacheNative;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonRegionNativeFactory extends RedissonRegionFactory {

    private static final long serialVersionUID = 4889779229712681692L;

    @Override
    public void start(Settings settings, Properties properties) throws CacheException {
        Set<Map.Entry<Object, Object>> entrySet = properties.entrySet();
        for (Map.Entry<Object, Object> entry : entrySet) {
            if (entry.getKey().toString().endsWith(RedissonRegionFactory.MAX_ENTRIES_SUFFIX)) {
                Integer value = Integer.valueOf(entry.getValue().toString());
                if (value > 0) {
                    throw new IllegalArgumentException(".eviction.max_entries setting can't be non-zero");
                }
            }
            if (entry.getKey().toString().endsWith(RedissonRegionFactory.MAX_IDLE_SUFFIX)) {
                Integer value = Integer.valueOf(entry.getValue().toString());
                if (value > 0) {
                    throw new IllegalArgumentException(".expiration.max_idle_time setting can't be non-zero");
                }
            }
        }
        super.start(settings, properties);
    }

    @Override
    protected RMapCache<Object, Object> getCache(String regionName, Properties properties, String defaultKey) {
        RMapCacheNative<Object, Object> cache = redisson.getMapCacheNative(regionName);
        return new MapCacheNativeWrapper<>(cache, (Redisson) redisson);
    }
    
}
