/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
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
 * </p>
 */

package com.dangdang.ddframe.rdb.sharding.executor;

import com.dangdang.ddframe.rdb.sharding.exception.ShardingJdbcException;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 多线程执行框架.
 * 
 * @author gaohongtao
 */
@Slf4j
public final class ExecutorEngine implements AutoCloseable {
    
    private final ListeningExecutorService executorService;
    
    public ExecutorEngine(final int executorSize) {
        executorService = MoreExecutors.listeningDecorator(new ThreadPoolExecutor(executorSize, executorSize, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactoryBuilder()
                        .setDaemon(true).setNameFormat("ShardingJDBC-%d").build()));
        MoreExecutors.addDelayedShutdownHook(executorService, 60, TimeUnit.SECONDS);
    }
    
    /**
     * 多线程执行任务.
     * 一组任务中,将第一个任务放在当前线程中执行,其余的任务放到线程池中运行.
     * 
     * 
     * @param inputs 输入参数
     * @param executeUnit 执行单元
     * @param <I> 入参类型
     * @param <O> 出参类型
     * @return 执行结果
     */
    public <I, O> List<O> execute(final Collection<I> inputs, final ExecuteUnit<I, O> executeUnit) {
        Iterator<I> iterator = inputs.iterator();
        if (!iterator.hasNext()) {
            return Collections.emptyList();
        }
        I firstInput = iterator.next();
        ListenableFuture<List<O>> restListFuture = asyncRun(Lists.newArrayList(iterator), executeUnit);
        O firstOutput;
        List<O> restOutputs;
        try {
            firstOutput = executeUnit.execute(firstInput);
            restOutputs = restListFuture.get();
            //CHECKSTYLE:OFF
        } catch (final Exception ex) {
            //CHECKSTYLE:ON
            ExecutorExceptionHandler.handleException(ex);
            return null;
        }
        List<O> result = Lists.newLinkedList(restOutputs);
        result.add(0, firstOutput);
        return result;
    }
    
    /**
     * 多线程执行任务并归并结果.
     * 
     * @param inputs 执行入参
     * @param executeUnit 执行单元
     * @param mergeUnit 合并结果单元
     * @param <I> 入参类型
     * @param <M> 中间结果类型
     * @param <O> 最终结果类型
     * @return 执行结果
     */
    public <I, M, O> O execute(final Collection<I> inputs, final ExecuteUnit<I, M> executeUnit, final MergeUnit<M, O> mergeUnit) {
        return mergeUnit.merge(execute(inputs, executeUnit));
    }
    
    private <I, O> ListenableFuture<List<O>> asyncRun(final Collection<I> inputs, final ExecuteUnit<I, O> executeUnit) {
        List<ListenableFuture<O>> result = new ArrayList<>(inputs.size());
        for (final I each : inputs) {
            result.add(executorService.submit(new Callable<O>() {
                
                @Override
                public O call() throws Exception {
                    return executeUnit.execute(each);
                }
            }));
        }
        return Futures.allAsList(result);
    }
    
    @Override
    public void close() {
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException ignored) {
        }
        if (!executorService.isTerminated()) {
            throw new ShardingJdbcException("ExecutorEngine can not been terminated");
        }
    }
}
