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
package org.redisson.api.options;

import java.time.Duration;

/**
 *
 * @author Nikita Koksharov
 *
 */
class BaseOptions<T extends InvocationOptions<T>, C> implements CodecOptions<T, C>, ObjectParams {

    private C codec;
    private int timeout;
    private int retryAttempts;
    private int retryInterval;

    @Override
    public T codec(C codec) {
        this.codec = codec;
        return (T) this;
    }

    @Override
    public T timeout(Duration timeout) {
        this.timeout = (int) timeout.toMillis();
        return (T) this;
    }

    @Override
    public T retryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
        return (T) this;
    }

    @Override
    public T retryInterval(Duration interval) {
        this.retryInterval = (int) interval.toMillis();
        return (T) this;
    }

    public int getTimeout() {
        return timeout;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public int getRetryInterval() {
        return retryInterval;
    }

    public C getCodec() {
        return codec;
    }

}
