/**
 * Copyright (c) 2013-2021 Nikita Koksharov
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
package org.redisson.client.handler;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.redisson.api.RFuture;
import org.redisson.client.RedisClientConfig;
import org.redisson.client.RedisConnection;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 
 * @author Nikita Koksharov
 *
 */
@Sharable
public class PingConnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PingConnectionHandler.class);
    
    private final RedisClientConfig config;

    public PingConnectionHandler(RedisClientConfig config) {
        this.config = config;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        RedisConnection connection = RedisConnection.getFrom(ctx.channel());
        connection.getConnectionPromise().onComplete((res, e) -> {
            if (e == null) {
                sendPing(ctx);
            }
        });
        ctx.fireChannelActive();
    }

    private void sendPing(ChannelHandlerContext ctx) {
        RedisConnection connection = RedisConnection.getFrom(ctx.channel());
        RFuture<String> future;
        if (connection.getUsage() == 0) {
            future = connection.async(StringCodec.INSTANCE, RedisCommands.PING);
        } else {
            future = null;
        }

        config.getTimer().newTimeout(timeout -> {
            if (connection.isClosed() || ctx.isRemoved()) {
                return;
            }

            if (connection.getUsage() == 0
                    && future != null
                        && (future.cancel(false) || !future.isSuccess())) {
                ctx.channel().close();
                if (future.cause() != null && !future.isCancelled()) {
                    log.error("Unable to send PING command over channel: " + ctx.channel(), future.cause());
                }
                log.debug("channel: {} closed due to PING response timeout set in {} ms", ctx.channel(), config.getPingConnectionInterval());
            } else {
                sendPing(ctx);
            }
        }, config.getPingConnectionInterval(), TimeUnit.MILLISECONDS);
    }


}
