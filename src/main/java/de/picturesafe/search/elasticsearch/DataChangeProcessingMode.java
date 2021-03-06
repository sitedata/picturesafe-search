/*
 * Copyright 2020 picturesafe media/data/bank GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.picturesafe.search.elasticsearch;

/**
 * Some operations might change the search index. Generally there exists two Processing-Modes to change a search
 * index:
 * <ul>
 *     <li>
 *          Background - Use this mode as far as possible!
 *          <br>
 *          This mode allows the application to handle data changes asynchronous in background. When the
 *          operation is finished the data might not have been changed down to the search index immediately. The
 *          changes might apply immediately, but in general they will apply some seconds later.
 *          <br>
 *          Processing data changes in the background or by keeping caches not in sync reduces the load of an
 *          application significantly.
 *     </li>
 *     <li>
 *          Blocking - Be careful using this mode!
 *          <br>
 *          When the operation is finished, all the changes are available in the search index.
 *          <br>
 *          Following searches will respect the data changes immediately.
 *          Using this mode can trigger additional load and may slow down the current use case and other use cases.
 *          <br>
 *          Changing too much data in this mode, or using this mode in too many parallel executions might
 *          trigger very heavy additional load, causing the applications to pause or block.
 *      </li>
 * </ul>
 */
public enum DataChangeProcessingMode {

    BACKGROUND(false),
    BLOCKING(true);

    private final boolean refresh;

    DataChangeProcessingMode(boolean refresh) {
        this.refresh = refresh;
    }

    public boolean isRefresh() {
        return refresh;
    }
}
