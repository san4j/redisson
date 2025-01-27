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
package org.redisson.client;

/**
 * Detects failed Redis node depending
 * on {@link #isNodeFailed()} method implementation.
 *
 * @author Nikita Koksharov
 *
 */
public interface FailedNodeDetector {

    void onConnectSuccessful();

    @Deprecated
    void onConnectFailed();

    default void onConnectFailed(Throwable cause) {
        onConnectFailed();
    }

    void onPingSuccessful();

    @Deprecated
    void onPingFailed();

    default void onPingFailed(Throwable cause) {
        onPingFailed();
    }

    void onCommandSuccessful();

    void onCommandFailed(Throwable cause);

    boolean isNodeFailed();

}
