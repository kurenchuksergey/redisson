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
package org.redisson.command;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import org.redisson.api.BatchOptions;
import org.redisson.api.BatchOptions.ExecutionMode;
import org.redisson.api.RFuture;
import org.redisson.client.RedisConnection;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandBatchService.Entry;
import org.redisson.connection.ConnectionManager;
import org.redisson.connection.NodeSource;
import org.redisson.connection.NodeSource.Redirect;
import org.redisson.liveobject.core.RedissonObjectBuilder;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedisCommonBatchExecutor extends RedisExecutor<Object, Void> {

    static final Logger log = LoggerFactory.getLogger(RedisCommonBatchExecutor.class);

    private final Entry entry;
    private final AtomicInteger slots;
    private final BatchOptions options;
    
    public RedisCommonBatchExecutor(NodeSource source, RPromise<Void> mainPromise,
                                    ConnectionManager connectionManager, BatchOptions options, Entry entry, AtomicInteger slots, RedissonObjectBuilder.ReferenceType referenceType) {
        super(entry.isReadOnlyMode(), source, null, null, null,
                mainPromise, false, connectionManager, null, referenceType);
        this.options = options;
        this.entry = entry;
        this.slots = slots;
        
        if (options.getRetryAttempts() > 0) {
            this.attempts = options.getRetryAttempts();
        }
        if (options.getRetryInterval() > 0) {
            this.retryInterval  = options.getRetryInterval();
        }
        if (options.getResponseTimeout() > 0) {
            this.responseTimeout = options.getResponseTimeout();
        }
        if (options.getSyncSlaves() > 0) {
            this.responseTimeout += options.getSyncTimeout();
        }
    }

    @Override
    protected void onException() {
        entry.clearErrors();
    }
    
    @Override
    protected void free() {
        free(entry);
    }
    
    private void free(Entry entry) {
        for (CommandData<?, ?> command : entry.getCommands()) {
            free(command.getParams());
        }
    }
    
    @Override
    protected void sendCommand(RPromise<Void> attemptPromise, RedisConnection connection) {
        boolean isAtomic = options.getExecutionMode() != ExecutionMode.IN_MEMORY;
        boolean isQueued = options.getExecutionMode() == ExecutionMode.REDIS_READ_ATOMIC 
                                || options.getExecutionMode() == ExecutionMode.REDIS_WRITE_ATOMIC;

        List<CommandData<?, ?>> list = new ArrayList<>(entry.getCommands().size());
        if (source.getRedirect() == Redirect.ASK) {
            RPromise<Void> promise = new RedissonPromise<Void>();
            list.add(new CommandData<>(promise, StringCodec.INSTANCE, RedisCommands.ASKING, new Object[] {}));
        } 
        for (CommandData<?, ?> c : entry.getCommands()) {
            if ((c.getPromise().isCancelled() || c.getPromise().isSuccess()) 
                    && !isWaitCommand(c) 
                        && !isAtomic) {
                // skip command
                continue;
            }
            list.add(c);
        }
        
        if (list.isEmpty()) {
            writeFuture = connection.getChannel().newPromise();
            attemptPromise.trySuccess(null);
            timeout.cancel();
            return;
        }

        sendCommand(connection, attemptPromise, list);
    }

    private void sendCommand(RedisConnection connection, RPromise<Void> attemptPromise, List<CommandData<?, ?>> list) {
        boolean isAtomic = options.getExecutionMode() != ExecutionMode.IN_MEMORY;
        boolean isQueued = options.getExecutionMode() == ExecutionMode.REDIS_READ_ATOMIC
                || options.getExecutionMode() == ExecutionMode.REDIS_WRITE_ATOMIC;

        CommandData<?, ?> lastCommand = connection.getLastCommand();
        if (lastCommand != null && options.isSkipResult()) {
            writeFuture = connection.getChannel().newPromise();
            lastCommand.getPromise().onComplete((r, e) -> {
                CommandData<?, ?> currentLastCommand = connection.getLastCommand();
                if (lastCommand != currentLastCommand && currentLastCommand != null) {
                    sendCommand(connection, attemptPromise, list);
                    return;
                }

                ChannelFuture wf = connection.send(new CommandsData(attemptPromise, list, options.isSkipResult(), isAtomic, isQueued, options.getSyncSlaves() > 0));
                wf.addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        ((ChannelPromise) writeFuture).trySuccess(future.getNow());
                    } else {
                        ((ChannelPromise) writeFuture).tryFailure(future.cause());
                    }
                });
            });
            return;
        }

        writeFuture = connection.send(new CommandsData(attemptPromise, list, options.isSkipResult(), isAtomic, isQueued, options.getSyncSlaves() > 0));
    }

    protected boolean isWaitCommand(CommandData<?, ?> c) {
        return c.getCommand().getName().equals(RedisCommands.WAIT.getName());
    }

    @Override
    protected void handleResult(RPromise<Void> attemptPromise, RFuture<RedisConnection> connectionFuture) throws ReflectiveOperationException {
        if (attemptPromise.isSuccess()) {
            if (slots.decrementAndGet() == 0) {
                mainPromise.trySuccess(null);
            }
        } else {
            mainPromise.tryFailure(attemptPromise.cause());
        }
    }
    
}
