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
package org.redisson;

import io.netty.buffer.ByteBuf;
import org.redisson.api.ObjectListener;
import org.redisson.api.RFuture;
import org.redisson.api.RList;
import org.redisson.api.RListMultimap;
import org.redisson.api.listener.ListAddListener;
import org.redisson.api.listener.ListRemoveListener;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.RedisStrictCommand;
import org.redisson.client.protocol.convertor.BooleanAmountReplayConvertor;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.misc.CompletableFutureWrapper;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * @author Nikita Koksharov
 *
 * @param <K> key
 * @param <V> value
 */
public class RedissonListMultimap<K, V> extends RedissonMultimap<K, V> implements RListMultimap<K, V> {

    private static final RedisStrictCommand<Boolean> LLEN_VALUE = new RedisStrictCommand<Boolean>("LLEN", new BooleanAmountReplayConvertor());

    public RedissonListMultimap(CommandAsyncExecutor connectionManager, String name) {
        super(connectionManager, name);
    }

    public RedissonListMultimap(Codec codec, CommandAsyncExecutor connectionManager, String name) {
        super(codec, connectionManager, name);
    }

    @Override
    public RFuture<Integer> sizeAsync() {
        return commandExecutor.evalReadAsync(getRawName(), codec, RedisCommands.EVAL_INTEGER,
                "local keys = redis.call('hgetall', KEYS[1]); " +
                "local size = 0; " +
                "for i, v in ipairs(keys) do " +
                    "if i % 2 == 0 then " +
                        "local name = ARGV[1] .. v; " +
                        "size = size + redis.call('llen', name); " +
                    "end;" +
                "end; " +
                "return size; ",
                Arrays.<Object>asList(getRawName()),
                prefix);
    }

    @Override
    public RFuture<Long> fastRemoveValueAsync(V... values) {
        List<Object> args = new ArrayList<>(values.length + 1);
        args.add(prefix);
        encodeMapValues(args, Arrays.asList(values));

        return commandExecutor.evalWriteAsync(getRawName(), codec, RedisCommands.EVAL_INTEGER,
                "local keys = redis.call('hgetall', KEYS[1]); " +
                "local size = 0; " +
                "for i, v in ipairs(keys) do " +
                    "if i % 2 == 0 then " +
                        "local name = ARGV[1] .. v; " +
                        "for j = 2, #ARGV, 1 do " +
                            "size = size + redis.call('lrem', name, 1, ARGV[j]); " +
                        "end; " +
                    "end;" +
                "end; " +
                "return size; ",
                Arrays.asList(getRawName()),
                args.toArray());
    }



    @Override
    public RFuture<Boolean> containsKeyAsync(Object key) {
        String keyHash = keyHash(key);
        String setName = getValuesName(keyHash);
        return commandExecutor.readAsync(getRawName(), codec, LLEN_VALUE, setName);
    }

    @Override
    public RFuture<Boolean> containsValueAsync(Object value) {
        ByteBuf valueState = encodeMapValue(value);

        return commandExecutor.evalReadAsync(getRawName(), codec, RedisCommands.EVAL_BOOLEAN,
                "local keys = redis.call('hgetall', KEYS[1]); " +
                "for i, v in ipairs(keys) do " +
                    "if i % 2 == 0 then " +
                        "local name = ARGV[2] .. v; " +

                        "local items = redis.call('lrange', name, 0, -1) " +
                        "for i=1,#items do " +
                            "if items[i] == ARGV[1] then " +
                                "return 1; " +
                            "end; " +
                        "end; " +
                    "end;" +
                "end; " +
                "return 0; ",
                Arrays.<Object>asList(getRawName()),
                valueState, prefix);
    }

    @Override
    public boolean containsEntry(Object key, Object value) {
        return get(containsEntryAsync(key, value));
    }

    @Override
    public RFuture<Boolean> containsEntryAsync(Object key, Object value) {
        ByteBuf valueState = encodeMapValue(value);

        String keyHash = keyHash(key);
        String setName = getValuesName(keyHash);

        return commandExecutor.evalReadAsync(getRawName(), codec, RedisCommands.EVAL_BOOLEAN,
                "local items = redis.call('lrange', KEYS[1], 0, -1) " +
                "for i=1,#items do " +
                    "if items[i] == ARGV[1] then " +
                        "return 1; " +
                    "end; " +
                "end; " +
                "return 0; ",
                Collections.<Object>singletonList(setName), valueState);
    }

    @Override
    public boolean put(K key, V value) {
        return get(putAsync(key, value));
    }

    @Override
    public RFuture<Boolean> putAsync(K key, V value) {
        ByteBuf keyState = encodeMapKey(key);
        String keyHash = hash(keyState);
        ByteBuf valueState = encodeMapValue(value);

        String setName = getValuesName(keyHash);
        return commandExecutor.evalWriteNoRetryAsync(getRawName(), codec, RedisCommands.EVAL_BOOLEAN,
                "redis.call('hsetnx', KEYS[1], ARGV[1], ARGV[2]); " +
                "redis.call('rpush', KEYS[2], ARGV[3]); " +
                "return 1; ",
            Arrays.<Object>asList(getRawName(), setName), keyState, keyHash, valueState);
    }

    @Override
    public RFuture<Boolean> removeAsync(Object key, Object value) {
        ByteBuf keyState = encodeMapKey(key);
        String keyHash = hash(keyState);
        ByteBuf valueState = encodeMapValue(value);

        String setName = getValuesName(keyHash);
        return commandExecutor.evalWriteAsync(getRawName(), codec, RedisCommands.EVAL_BOOLEAN,
                "local res = redis.call('lrem', KEYS[2], 1, ARGV[2]); "
              + "if res == 1 and redis.call('llen', KEYS[2]) == 0 then "
                  + "redis.call('hdel', KEYS[1], ARGV[1]); "
              + "end; "
              + "return res; ",
            Arrays.<Object>asList(getRawName(), setName), keyState, valueState);
    }

    @Override
    public RFuture<Boolean> putAllAsync(K key, Iterable<? extends V> values) {
        List<Object> params = new ArrayList<Object>();
        ByteBuf keyState = encodeMapKey(key);
        params.add(keyState);
        String keyHash = hash(keyState);
        params.add(keyHash);
        for (Object value : values) {
            ByteBuf valueState = encodeMapValue(value);
            params.add(valueState);
        }

        String setName = getValuesName(keyHash);
        return commandExecutor.evalWriteNoRetryAsync(getRawName(), codec, RedisCommands.EVAL_BOOLEAN_AMOUNT,
                  "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); " +
                        "local n = 0; " +
                        "for i=3, #ARGV, 5000 do " +
                            "n = n + redis.call('rpush', KEYS[2], unpack(ARGV, i, math.min(i+4999, table.getn(ARGV)))) " +
                        "end; " +
                        "return n; ",
            Arrays.<Object>asList(getRawName(), setName), params.toArray());
    }


    @Override
    public RList<V> get(K key) {
        String keyHash = keyHash(key);
        String setName = getValuesName(keyHash);

        return new InnerList(setName, key);
    }

    @Override
    public List<V> getAll(K key) {
        return (List<V>) get(getAllAsync(key));
    }

    @Override
    public RFuture<Collection<V>> getAllAsync(K key) {
        String keyHash = keyHash(key);
        String setName = getValuesName(keyHash);

        return commandExecutor.readAsync(getRawName(), codec, RedisCommands.LRANGE, setName, 0, -1);
    }

    @Override
    public List<V> removeAll(Object key) {
        return (List<V>) get(removeAllAsync(key));
    }

    @Override
    public RFuture<Collection<V>> removeAllAsync(Object key) {
        ByteBuf keyState = encodeMapKey(key);
        String keyHash = hash(keyState);

        String setName = getValuesName(keyHash);
        return commandExecutor.evalWriteAsync(getRawName(), codec, RedisCommands.EVAL_LIST,
                "redis.call('hdel', KEYS[1], ARGV[1]); " +
                "local members = redis.call('lrange', KEYS[2], 0, -1); " +
                "redis.call('del', KEYS[2]); " +
                "return members; ",
            Arrays.<Object>asList(getRawName(), setName), keyState);
    }

    @Override
    public List<V> replaceValues(K key, Iterable<? extends V> values) {
        return (List<V>) get(replaceValuesAsync(key, values));
    }

    @Override
    public void fastReplaceValues(final K key, final Iterable<? extends V> values) {
        get(fastReplaceValuesAsync(key, values));
    }

    @Override
    public RFuture<Collection<V>> replaceValuesAsync(K key, Iterable<? extends V> values) {
        List<Object> params = new ArrayList<Object>();
        ByteBuf keyState = encodeMapKey(key);
        params.add(keyState);
        String keyHash = hash(keyState);
        params.add(keyHash);
        for (Object value : values) {
            ByteBuf valueState = encodeMapValue(value);
            params.add(valueState);
        }

        String setName = getValuesName(keyHash);
        return commandExecutor.evalWriteNoRetryAsync(getRawName(), codec, RedisCommands.EVAL_LIST,
                "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); " +
                "local members = redis.call('lrange', KEYS[2], 0, -1); " +
                "redis.call('del', KEYS[2]); " +
                "if #ARGV > 2 then " +
                    "for i=3, #ARGV, 5000 do " +
                        "redis.call('rpush', KEYS[2], unpack(ARGV, i, math.min(i+4999, table.getn(ARGV)))) " +
                    "end; " +
                "end; " +
                "return members; ",
            Arrays.<Object>asList(getRawName(), setName), params.toArray());
    }

    @Override
    public RFuture<Void> fastReplaceValuesAsync(K key, Iterable<? extends V> values) {
        List<Object> params = new ArrayList<Object>();
        ByteBuf keyState = encodeMapKey(key);
        params.add(keyState);
        String keyHash = hash(keyState);
        params.add(keyHash);
        for (Object value : values) {
            ByteBuf valueState = encodeMapValue(value);
            params.add(valueState);
        }

        String setName = getValuesName(keyHash);
        return commandExecutor.evalWriteNoRetryAsync(getRawName(), codec, RedisCommands.EVAL_VOID,
                "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); " +
                "redis.call('del', KEYS[2]); " +
                "if #ARGV > 2 then " +
                    "for i=3, #ARGV, 5000 do " +
                        "redis.call('rpush', KEYS[2], unpack(ARGV, i, math.min(i+4999, table.getn(ARGV)))) " +
                    "end; " +
                "end; ",
            Arrays.<Object>asList(getRawName(), setName), params.toArray());
    }

    @Override
    Iterator<V> valuesIterator() {
        return new RedissonListMultimapIterator<K, V, V>(this, commandExecutor, codec) {
            @Override
            V getValue(V entry) {
                return (V) entry;
            }
        };
    }

    @Override
    RedissonMultiMapIterator<K, V, Entry<K, V>> entryIterator() {
        return new RedissonListMultimapIterator<>(this, commandExecutor, codec);
    }

    @Override
    protected <T extends ObjectListener> int addListener(String name, T listener, BiConsumer<T, String> consumer) {
        if (listener instanceof ListAddListener
                || listener instanceof ListRemoveListener) {
            String prefix = getValuesName("");
            return addListener(name, listener, consumer, m -> m.startsWith(prefix));
        }
        return super.addListener(name, listener, consumer);
    }

    @Override
    protected <T extends ObjectListener> RFuture<Integer> addListenerAsync(String name, T listener, BiConsumer<T, String> consumer) {
        if (listener instanceof ListAddListener
                || listener instanceof ListRemoveListener) {
            String prefix = getValuesName("");
            return addListenerAsync(name, listener, consumer, m -> m.startsWith(prefix));
        }
        return super.addListenerAsync(name, listener, consumer);
    }

    @Override
    public int addListener(ObjectListener listener) {
        if (listener instanceof ListAddListener) {
            return addListener("__keyevent@*:rpush", (ListAddListener) listener, ListAddListener::onListAdd);
        }
        if (listener instanceof ListRemoveListener) {
            return addListener("__keyevent@*:lrem", (ListRemoveListener) listener, ListRemoveListener::onListRemove);
        }

        return super.addListener(listener);
    }

    @Override
    public RFuture<Integer> addListenerAsync(ObjectListener listener) {
        if (listener instanceof ListAddListener) {
            return addListenerAsync("__keyevent@*:rpush", (ListAddListener) listener, ListAddListener::onListAdd);
        }
        if (listener instanceof ListRemoveListener) {
            return addListenerAsync("__keyevent@*:lrem", (ListRemoveListener) listener, ListRemoveListener::onListRemove);
        }

        return super.addListenerAsync(listener);
    }

    @Override
    public void removeListener(int listenerId) {
        removeListener(listenerId, "__keyevent@*:rpush", "__keyevent@*:lrem");
        super.removeListener(listenerId);
    }

    @Override
    public RFuture<Void> removeListenerAsync(int listenerId) {
        return removeListenerAsync(listenerId, "__keyevent@*:rpush", "__keyevent@*:lrem");
    }

    protected class InnerList extends RedissonList<V> {

        private final String setName;
        private final K key;

        public InnerList(String setName, K key) {
            super(RedissonListMultimap.this.codec, RedissonListMultimap.this.commandExecutor, setName, null);
            this.setName = setName;
            this.key = key;
        }

        @Override
        public RFuture<Boolean> addAsync(V value) {
            return RedissonListMultimap.this.putAsync(key, value);
        }

        @Override
        public RFuture<Boolean> addAllAsync(Collection<? extends V> c) {
            return RedissonListMultimap.this.putAllAsync(key, c);
        }

        @Override
        public RFuture<Boolean> removeAsync(Object value) {
            return RedissonListMultimap.this.removeAsync(key, value);
        }

        @Override
        public RFuture<Boolean> removeAllAsync(Collection<?> c) {
            if (c.isEmpty()) {
                return new CompletableFutureWrapper<>(false);
            }

            List<Object> args = new ArrayList<>(c.size() + 1);
            args.add(encodeMapKey(key));
            encode(args, c);

            return commandExecutor.evalWriteAsync(RedissonListMultimap.this.getRawName(), codec, RedisCommands.EVAL_BOOLEAN,
                    "local v = 0 " +
                    "for i = 2, #ARGV, 1 do "
                        + "if redis.call('lrem', KEYS[2], 0, ARGV[i]) == 1 then "
                            + "v = 1; "
                        + "end "
                   +"end "
                  + "if v == 1 and redis.call('exists', KEYS[2]) == 0 then "
                      + "redis.call('hdel', KEYS[1], ARGV[1]); "
                   +"end "
                  + "return v",
                Arrays.asList(RedissonListMultimap.this.getRawName(), setName),
                args.toArray());
        }

        @Override
        public RFuture<Boolean> deleteAsync() {
            ByteBuf keyState = encodeMapKey(key);
            return RedissonListMultimap.this.fastRemoveAsync(Arrays.asList(keyState),
                    Arrays.asList(RedissonListMultimap.this.getRawName(), setName), RedisCommands.EVAL_BOOLEAN_AMOUNT);
        }

        @Override
        public RFuture<Boolean> clearExpireAsync() {
            throw new UnsupportedOperationException("This operation is not supported for SetMultimap values Set");
        }

        @Override
        public RFuture<Boolean> expireAsync(long timeToLive, TimeUnit timeUnit, String param, String... keys) {
            throw new UnsupportedOperationException("This operation is not supported for SetMultimap values Set");
        }

        @Override
        protected RFuture<Boolean> expireAtAsync(long timestamp, String param, String... keys) {
            throw new UnsupportedOperationException("This operation is not supported for SetMultimap values Set");
        }

        @Override
        public RFuture<Long> remainTimeToLiveAsync() {
            throw new UnsupportedOperationException("This operation is not supported for SetMultimap values Set");
        }

        @Override
        public RFuture<Void> renameAsync(String newName) {
            throw new UnsupportedOperationException("This operation is not supported for SetMultimap values Set");
        }

        @Override
        public RFuture<Boolean> renamenxAsync(String newName) {
            throw new UnsupportedOperationException("This operation is not supported for SetMultimap values Set");
        }

    }
}
