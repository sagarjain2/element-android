/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.matrix.android.internal.session.room.timeline

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.query.findIncludingEvent
import im.vector.matrix.android.internal.di.SessionDatabase
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.session.filter.FilterRepository
import im.vector.matrix.android.internal.session.room.RoomAPI
import im.vector.matrix.android.internal.task.Task
import im.vector.matrix.android.internal.util.awaitTransaction
import org.greenrobot.eventbus.EventBus
import javax.inject.Inject

internal interface FetchTokenAndPaginateTask : Task<FetchTokenAndPaginateTask.Params, TokenChunkEventPersistor.Result> {

    data class Params(
            val roomId: String,
            val lastKnownEventId: String,
            val direction: PaginationDirection,
            val limit: Int
    )
}

internal class DefaultFetchTokenAndPaginateTask @Inject constructor(
        private val roomAPI: RoomAPI,
        @SessionDatabase private val monarchy: Monarchy,
        private val filterRepository: FilterRepository,
        private val paginationTask: PaginationTask,
        private val eventBus: EventBus
) : FetchTokenAndPaginateTask {

    override suspend fun execute(params: FetchTokenAndPaginateTask.Params): TokenChunkEventPersistor.Result {
        val filter = filterRepository.getRoomFilter()
        val response = executeRequest<EventContextResponse>(eventBus) {
            apiCall = roomAPI.getContextOfEvent(params.roomId, params.lastKnownEventId, 0, filter)
        }
        val fromToken = if (params.direction == PaginationDirection.FORWARDS) {
            response.end
        } else {
            response.start
        }
                ?: throw IllegalStateException("No token found")

        monarchy.awaitTransaction { realm ->
            val chunkToUpdate = ChunkEntity.findIncludingEvent(realm, params.lastKnownEventId)
            if (params.direction == PaginationDirection.FORWARDS) {
                chunkToUpdate?.nextToken = fromToken
            } else {
                chunkToUpdate?.prevToken = fromToken
            }
        }
        val paginationParams = PaginationTask.Params(
                roomId = params.roomId,
                from = fromToken,
                direction = params.direction,
                limit = params.limit
        )
        return paginationTask.execute(paginationParams)
    }
}