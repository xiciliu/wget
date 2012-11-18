package com.github.axet.wget;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.axet.wget.info.DownloadError;
import com.github.axet.wget.info.DownloadInfo;
import com.github.axet.wget.info.DownloadInfo.Part;
import com.github.axet.wget.info.DownloadMultipartError;
import com.github.axet.wget.info.DownloadRetry;

public class DirectMultipart extends Direct {

    static public final int THREAD_COUNT = 3;

    Vector<Part> downloads = new Vector<Part>();

    static class LimitDownloader extends ThreadPoolExecutor {

        Object lock = new Object();

        List<Throwable> t = new LinkedList<Throwable>();

        public LimitDownloader() {
            super(THREAD_COUNT, THREAD_COUNT, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);

            synchronized (lock) {
                lock.notifyAll();
            }
        }

        public boolean active() {
            return getTasks() > 0;
        }

        public void waitUntilNextTaskEnds() {
            synchronized (lock) {
                if (getActiveCount() > 0) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        int getTasks() {
            return getActiveCount() + getQueue().size();
        }

        @Override
        public void execute(Runnable command) {
            // do not allow to put more tasks then threads.
            // if happens - wait until thread ends.
            synchronized (lock) {
                if (getTasks() >= THREAD_COUNT) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            super.execute(command);
        }

        public List<Throwable> errors() {
            synchronized (lock) {
                return t;
            }
        }
    }

    LimitDownloader worker = new LimitDownloader();

    /**
     * 
     * @param info
     *            download file information
     * @param target
     *            target file
     * @param stop
     *            multithread stop command
     * @param notify
     *            progress notify call
     */
    public DirectMultipart(DownloadInfo info, File target, AtomicBoolean stop, Runnable notify) {
        super(info, target, stop, notify);
    }

    void part(Part part) {
        try {
            RandomAccessFile fos = null;

            try {
                URL url = info.getSource();

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);

                File f = target;

                fos = new RandomAccessFile(f, "rw");

                long start = part.getStart() + part.getCount();
                long end = part.getEnd();

                conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                fos.seek(start);

                byte[] bytes = new byte[BUF_SIZE];
                int read = 0;

                BufferedInputStream binaryreader = new BufferedInputStream(conn.getInputStream());

                boolean localStop = false;

                while (!stop.get() && !localStop && (read = binaryreader.read(bytes)) > 0) {
                    // ensure we do not download more then part size.
                    // if so cut bytes and stop download
                    long partEnd = part.getLength() - part.getCount();
                    if (read > partEnd) {
                        read = (int) partEnd;
                        localStop = true;
                    }

                    fos.write(bytes, 0, read);
                    part.setCount(part.getCount() + read);

                    info.calculate();

                    notify.run();
                }

                binaryreader.close();
            } finally {
                if (fos != null)
                    fos.close();
            }
        } catch (SocketException e) {
            throw new DownloadRetry(e);
        } catch (ProtocolException e) {
            throw new DownloadRetry(e);
        } catch (HttpRetryException e) {
            throw new DownloadRetry(e);
        } catch (InterruptedIOException e) {
            throw new DownloadRetry(e);
        } catch (UnknownHostException e) {
            throw new DownloadRetry(e);
        } catch (IOException e) {
            // all other io excetption including FileNotFoundException should
            // stop downloading.
            throw new DownloadError(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // return next part to download. ensure this part is not done() and not
    // currently downloading
    Part getPart() {
        for (Part p : info.getParts()) {
            if (p.done())
                continue;
            if (downloads.contains(p))
                continue;

            return p;
        }

        return null;
    }

    void download(final Part p) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    part(p);
                } catch (RuntimeException e) {
                    synchronized (worker.lock) {
                        worker.t.add(e);
                    }
                } finally {
                    downloads.remove(p);
                }
            }
        });

        downloads.add(p);
    }

    /**
     * return true, when thread pool empty, and here is no unfinished parts to
     * download
     * 
     * @return true - done. false - not done yet
     */
    boolean done() {
        if (stop.get())
            return true;
        if (worker.active())
            return false;
        if (getPart() != null)
            return false;

        return true;
    }

    public void download() {
        try {
            while (!done()) {
                Part p = getPart();
                if (p != null) {
                    download(p);
                } else {
                    worker.waitUntilNextTaskEnds();
                }

                // if we start to receive errors. stop add new tasks and wait
                // until all active tasks && queue will be emptyied
                if (!worker.errors().isEmpty()) {
                    while (worker.getTasks() > 0) {
                        worker.waitUntilNextTaskEnds();
                    }

                    // ok all thread stoped. now throw the exception and let app
                    // deal with the errors
                    throw new DownloadMultipartError(worker.errors());
                }
            }
        } finally {
            worker.shutdown();
        }
    }
}
