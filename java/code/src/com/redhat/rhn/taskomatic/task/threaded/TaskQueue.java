/**
 * Copyright (c) 2009 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.rhn.taskomatic.task.threaded;

import EDU.oswego.cs.dl.util.concurrent.Channel;
import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;
import EDU.oswego.cs.dl.util.concurrent.PooledExecutor;

import com.redhat.rhn.common.hibernate.HibernateFactory;

import java.util.List;

/**
 * Generic threaded queue suitable for use wherever Taskomatic
 * tasks need to process a number of work items in parallel.
 * @version $Rev$
 */
public class TaskQueue implements Runnable {
    
    private QueueDriver queueDriver;
    private Channel workers = new LinkedQueue();
    private PooledExecutor executor = null;
    private int executingWorkers = 0;
    private int queueSize = 0;
    private byte[] emptyQueueWait = new byte[0];
    
    /**
     * Store the QueueDriver instance used when run() is called
     * @param driver to be used as the current QueueDriver
     */
    public void setQueueDriver(QueueDriver driver) {
        queueDriver = driver;
    }
    
    /**
     * Get the current QueueDriver
     * @return current QueueDriver
     */
    public QueueDriver getQueueDriver() {
        return queueDriver;
    }
    
    /**
     * Callback all workers should call when starting
     * to process work
     */
    public synchronized void workerStarting() {
        executingWorkers++;
    }
    
    /**
     * Callback all workers should call when
     * finished with their work item
     */
    public synchronized void workerDone() {
        executingWorkers--;
        queueSize--;
        if (executingWorkers < 0) {
            executingWorkers = 0;
        }
        if (queueSize < 0) {
            queueSize = 0;
        }
        if (executingWorkers == 0) {
            synchronized (emptyQueueWait) {
                emptyQueueWait.notifyAll();
            }
        }
    }
    
    /**
     * Returns the number of currently executing workers
     * This should never be more than the thread pool's 
     * maximum size
     * @return number of currently executing workers
     */
    public int getExecutingWorkerCount() {
        return executingWorkers;
    }
    
    /**
     * Returns the number of workers pending
     * @return number of workers pending
     */
    public int getQueueSize() {
        return queueSize;
    }
    
    /**
     * {@inheritDoc}
     */
    public void run() {
        setupQueue();
        List candidates = queueDriver.getCandidates();
        queueSize = candidates.size();
        while (candidates.size() > 0 && queueDriver.canContinue()) {
            Object candidate = (Object) candidates.remove(0);
            QueueWorker worker = queueDriver.makeWorker(candidate);
            worker.setParentQueue(this);
            try {
                queueDriver.getLogger().debug("Putting worker");
                workers.put(worker);
                queueDriver.getLogger().debug("Put worker");
            }
            catch (InterruptedException e) {
                queueDriver.getLogger().error(e);
                HibernateFactory.commitTransaction();
                HibernateFactory.closeSession();
                HibernateFactory.getSession();
                return;
            }
        }
    }
    
    /**
     * Waits indefinitely until the queue has emptied of all workers
     * @throws InterruptedException the wait is interrupted
     */
    public void waitForEmptyQueue() throws InterruptedException {
        synchronized (emptyQueueWait) {
            emptyQueueWait.wait();
        }
    }
    
    void shutdown() {
        executor.shutdownNow();
        while (!executor.isTerminatedAfterShutdown()) {
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                queueDriver.getLogger().error(e);
                return;
            }
        }  
    }
    
    private void setupQueue() {
        if (executor != null) {
            executor.shutdownAfterProcessingCurrentlyQueuedTasks();
            executor = null;
        }
        int maxPoolSize = queueDriver.getMaxWorkers();
        executor = new PooledExecutor(workers);
        executor.setThreadFactory(new TaskThreadFactory());
        executor.setKeepAliveTime(5000);        
        executor.setMinimumPoolSize(1);
        executor.setMaximumPoolSize(maxPoolSize);
        executor.createThreads(maxPoolSize);
    }
}
