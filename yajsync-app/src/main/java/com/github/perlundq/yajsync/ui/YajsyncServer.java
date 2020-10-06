/*
 * A simple rsync command line server implementation
 *
 * Copyright (C) 1996-2011 by Andrew Tridgell, Wayne Davison, and others
 * Copyright (C) 2013-2015 Per Lundqvist
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.perlundq.yajsync.ui;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.perlundq.yajsync.RsyncServer;
import com.github.perlundq.yajsync.internal.channels.ChannelException;
import com.github.perlundq.yajsync.internal.util.ArgumentParser;
import com.github.perlundq.yajsync.internal.util.ArgumentParsingError;
import com.github.perlundq.yajsync.internal.util.Environment;
import com.github.perlundq.yajsync.internal.util.Option;
import com.github.perlundq.yajsync.internal.util.Util;
import com.github.perlundq.yajsync.net.DuplexByteChannel;
import com.github.perlundq.yajsync.net.SSLServerChannelFactory;
import com.github.perlundq.yajsync.net.ServerChannel;
import com.github.perlundq.yajsync.net.ServerChannelFactory;
import com.github.perlundq.yajsync.net.StandardServerChannelFactory;
import com.github.perlundq.yajsync.server.module.ModuleException;
import com.github.perlundq.yajsync.server.module.ModuleProvider;
import com.github.perlundq.yajsync.server.module.Modules;


public final class YajsyncServer
{
    private static final Logger _log =
        Logger.getLogger(YajsyncServer.class.getName());
    private static final int THREAD_FACTOR = 4;
    private boolean _isTLS;
    private CountDownLatch _isListeningLatch;
    private int _numThreads = Runtime.getRuntime().availableProcessors() *
                              THREAD_FACTOR;
    private int _port = RsyncServer.DEFAULT_LISTEN_PORT;
    private int _verbosity;
    private InetAddress _address = InetAddress.getLoopbackAddress();
    private int _timeout = 0;
    private ModuleProvider _moduleProvider = ModuleProvider.getDefault();
    private PrintStream _out = System.out;
    private PrintStream _err = System.err;
    private final RsyncServer.Builder _serverBuilder = new RsyncServer.Builder();


    public YajsyncServer() {}

    public YajsyncServer setStandardOut(PrintStream out)
    {
        _out = out;
        return this;
    }

    public YajsyncServer setStandardErr(PrintStream err)
    {
        _err = err;
        return this;
    }

    public YajsyncServer setIsListeningLatch(CountDownLatch isListeningLatch)
    {
        _isListeningLatch = isListeningLatch;
        return this;
    }

    public void setModuleProvider(ModuleProvider moduleProvider)
    {
        _moduleProvider = moduleProvider;
    }

    private Iterable<Option> options()
    {
        List<Option> options = new LinkedList<>();
        options.add(Option.newStringOption(Option.Policy.OPTIONAL, "charset", "",
                                           "which charset to use (default UTF-8)",
                                           option -> {
                                               String charsetName = (String) option.getValue();
                                               try {
                                                   Charset charset = Charset.forName(charsetName);
                                                   _serverBuilder.charset(charset);
                                                   return ArgumentParser.Status.CONTINUE;
                                               } catch (IllegalCharsetNameException |
                                                        UnsupportedCharsetException e) {
                                                   throw new ArgumentParsingError(String.format(
                                                           "failed to set character set to %s: %s",
                                                           charsetName, e));
                                               }}));

        options.add(Option.newWithoutArgument(Option.Policy.OPTIONAL, "verbose", "v",
                                              String.format("output verbosity (default %d)",
                                                            _verbosity),
                                              option -> {
                                                  _verbosity++;
                                                  return ArgumentParser.Status.CONTINUE;
                                              }));

        options.add(Option.newStringOption(Option.Policy.OPTIONAL, "address", "",
                                           String.format("address to bind to (default %s)",
                                                         _address),
                                           option -> {
                                               try {
                                                   String name = (String) option.getValue();
                                                   _address = InetAddress.getByName(name);
                                                   return ArgumentParser.Status.CONTINUE;
                                               } catch (UnknownHostException e) {
                                                   throw new ArgumentParsingError(e);
                                               }}));

        options.add(Option.newIntegerOption(Option.Policy.OPTIONAL, "port", "",
                                            String.format("port number to listen on (default %d)",
                                                          _port),
                                            option -> {
                                                _port = (int) option.getValue();
                                                return ArgumentParser.Status.CONTINUE;
                                            }));

        options.add(Option.newIntegerOption(Option.Policy.OPTIONAL, "threads", "",
                                            String.format("size of thread pool (default %d)",
                                                          _numThreads),
                                            option -> {
                                                _numThreads = (int) option.getValue();
                                                return ArgumentParser.Status.CONTINUE;
                                            }));

        String deferredWriteHelp = "receiver defers writing into target tempfile as long as " +
                                   "possible to reduce I/O, at the cost of highly increased risk " +
                                   "of the file being modified by a process already having it " +
                                   "opened (default false)";

        options.add(Option.newWithoutArgument(Option.Policy.OPTIONAL, "defer-write", "",
                                              deferredWriteHelp,
                                              option -> {
                                                  _serverBuilder.isDeferWrite(true);
                                                  return ArgumentParser.Status.CONTINUE;
                                              }));

        options.add(Option.newIntegerOption(Option.Policy.OPTIONAL, "timeout", "",
                                            "set I/O timeout in seconds",
                                            option -> {
                                                int timeout = (int) option.getValue();
                                                if (timeout < 0) {
                                                    throw new ArgumentParsingError(String.format(
                                                            "invalid timeout %d - must be " +
                                                            "greater than or equal to 0", timeout));
                                                }
                                                _timeout = timeout * 1000;
                                                // Timeout socket operations depend on
                                                // ByteBuffer.array and ByteBuffer.arrayOffset.
                                                // Disable direct allocation if the resulting
                                                // ByteBuffer won't have an array.
                                                if (timeout > 0 &&
                                                    !Environment.hasAllocateDirectArray())
                                                {
                                                    Environment.setAllocateDirect(false);
                                                }
                                                return ArgumentParser.Status.CONTINUE;
                                            }));

        options.add(Option.newWithoutArgument(Option.Policy.OPTIONAL, "tls", "",
                                              String.format("tunnel all data over TLS/SSL " +
                                                            "(default %s)", _isTLS),
                                              option -> {
                                                  _isTLS = true;
                                                  // SSLChannel.read and SSLChannel.write depends on
                                                  // ByteBuffer.array and ByteBuffer.arrayOffset.
                                                  // Disable direct allocation if the resulting
                                                  // ByteBuffer won't have an array.
                                                  if (!Environment.hasAllocateDirectArray()) {
                                                      Environment.setAllocateDirect(false);
                                                  }
                                                  return ArgumentParser.Status.CONTINUE;
                                              }));

        return options;
    }

    private Callable<Boolean> createCallable(final RsyncServer server,
                                             final DuplexByteChannel sock,
                                             final boolean isInterruptible)
    {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() {
                boolean isOK = false;
                try {
                    Modules modules;
                    if (sock.peerPrincipal().isPresent()) {
                        if (_log.isLoggable(Level.FINE)) {
                            _log.fine(String.format("%s connected from %s",
                                                    sock.peerPrincipal().get(),
                                                    sock.peerAddress()));
                        }
                        modules = _moduleProvider.newAuthenticated(
                                                        sock.peerAddress(),
                                                        sock.peerPrincipal().get());
                    } else {
                        if (_log.isLoggable(Level.FINE)) {
                            _log.fine("got anonymous connection from " +
                                      sock.peerAddress());
                        }
                        modules = _moduleProvider.newAnonymous(
                                                        sock.peerAddress());
                    }
                    isOK = server.serve(modules, sock, sock, isInterruptible);
                } catch (ModuleException e) {
                    if (_log.isLoggable(Level.SEVERE)) {
                        _log.log(Level.SEVERE, String.format(
                            "Error: failed to initialise modules for " +
                            "principal %s using ModuleProvider %s: %s%n",
                                sock.peerPrincipal().get(), _moduleProvider, e),e);
                    }
                } catch (ChannelException e) {
                    if (_log.isLoggable(Level.SEVERE)) {
                        _log.log(Level.SEVERE,"Error: communication closed with peer: " +
                                    e.getMessage(),e);
                    }
                } catch (Throwable t) {
                    if (_log.isLoggable(Level.SEVERE)) {
                        _log.log(Level.SEVERE, "", t);
                    }
                } finally {
                    try {
                        sock.close();
                    } catch (IOException e) {
                        if (_log.isLoggable(Level.SEVERE)) {
                            _log.log( Level.SEVERE, String.format( "Got error during close of socket %s: %s",
                                            sock, e.getMessage() ), e );
                        }
                    }
                }

                if (_log.isLoggable(Level.FINE)) {
                    _log.fine("Thread exit status: " + (isOK ? "OK" : "ERROR"));
                }
                return isOK;
            }
        };
    }

    public int start(String[] args) throws IOException, InterruptedException
    {
        ArgumentParser argsParser = ArgumentParser.newNoUnnamed(getClass().getSimpleName());
        try {
            argsParser.addHelpTextDestination(_out);
            for (Option o : options()) {
                argsParser.add(o);
            }
            for (Option o : _moduleProvider.options()) {
                argsParser.add(o);
            }
            ArgumentParser.Status rc = argsParser.parse(Arrays.asList(args));   // throws ArgumentParsingError
            if (rc != ArgumentParser.Status.CONTINUE) {
                return rc == ArgumentParser.Status.EXIT_OK ? 0 : 1;
            }
        } catch (ArgumentParsingError e) {
            _err.println(e.getMessage());
            _err.println(argsParser.toUsageString());
            return -1;
        }

        Level logLevel = Util.getLogLevelForNumber(Util.WARNING_LOG_LEVEL_NUM +
                                                   _verbosity);
        Util.setRootLogLevel(logLevel);

        ServerChannelFactory socketFactory =
            _isTLS ? new SSLServerChannelFactory().setWantClientAuth(true)
                   : new StandardServerChannelFactory();

        socketFactory.setReuseAddress(true);
        //socketFactory.setKeepAlive(true);
        boolean isInterruptible = !_isTLS;
        ExecutorService executor = Executors.newFixedThreadPool(_numThreads);
        RsyncServer server = _serverBuilder.build(executor);

        try (ServerChannel listenSock = socketFactory.open(_address, _port, _timeout)) {  // throws IOException
            if (_isListeningLatch != null) {
                _isListeningLatch.countDown();
            }
            while (true) {
                DuplexByteChannel sock = listenSock.accept();                   // throws IOException
                Callable<Boolean> c = createCallable(server, sock,
                                                     isInterruptible);
                executor.submit(c);                                             // NOTE: result discarded
            }
        } finally {
            if (_log.isLoggable(Level.INFO)) {
                _log.info("shutting down...");
            }
            executor.shutdown();
            _moduleProvider.close();
            while (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                _log.info("some sessions are still running, waiting for them " +
                          "to finish before exiting");
            }
            if (_log.isLoggable(Level.INFO)) {
                _log.info("done");
            }
        }
    }
}
