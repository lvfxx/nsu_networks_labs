package ru.nsu.fit.semenov.portforwarder;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

import static java.nio.channels.SelectionKey.*;
import static ru.nsu.fit.semenov.portforwarder.SocketChannelRef.SocketChannelSide.CLIENT;
import static ru.nsu.fit.semenov.portforwarder.SocketChannelRef.SocketChannelSide.DESTINATION;

public class PortForwarder implements Runnable {
    private static final int BACKLOG = 10;

    private final InetSocketAddress localSocketAddress;
    private final InetSocketAddress destSocketAddress;

    public PortForwarder(int lport, @NotNull String rhost, int rport) {
        localSocketAddress = new InetSocketAddress(lport);

        destSocketAddress = new InetSocketAddress(rhost, rport);
        if (destSocketAddress.isUnresolved()) {
            throw new IllegalArgumentException("Unable to resolve specified host");
        }
    }

    @Override
    public void run() {
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open();
             Selector selector = Selector.open()) {
            serverSocket.bind(localSocketAddress, BACKLOG);
            serverSocket.configureBlocking(false);

            serverSocket.register(selector, OP_ACCEPT);

            while (true) {
                selector.select();

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();

                    if (key.isAcceptable() && key.isValid()) {
                        handleAccept(key);
                    }
                    if (key.isConnectable() && key.isValid()) {
                        handleConnect(key);
                    }
                    if (key.isReadable() && key.isValid()) {
                        handleRead(key);
                    }
                    if (key.isWritable() && key.isValid()) {
                        handleWrite(key);
                    }

                    iter.remove();
                }
            }
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }

    private void handleAccept(SelectionKey selectionKey) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey.channel();

        SocketChannel clientSocketChannel = serverSocketChannel.accept();
        clientSocketChannel.configureBlocking(false);

        SocketChannel destSocketChannel = SocketChannel.open();
        destSocketChannel.configureBlocking(false);

        destSocketChannel.connect(destSocketAddress);

        SelectionKey clientSelectionKey = clientSocketChannel.register(selectionKey.selector(), 0);
        SelectionKey destSelectionKey = destSocketChannel.register(selectionKey.selector(), OP_CONNECT);

        ForwardingData forwardingData =
                new ForwardingData(clientSocketChannel, destSocketChannel, clientSelectionKey, destSelectionKey);

        clientSelectionKey.attach(new SocketChannelRef(forwardingData, CLIENT));
        destSelectionKey.attach(new SocketChannelRef(forwardingData, DESTINATION));
    }

    private void handleConnect(SelectionKey selectionKey) throws IOException {
        SocketChannel destSocketChannel = (SocketChannel) selectionKey.channel();
        SocketChannelRef socketChannelRef = (SocketChannelRef) selectionKey.attachment();
        ForwardingData forwardingData = socketChannelRef.getForwardingData();

        try {
            if (!destSocketChannel.finishConnect()) {
                throw new AssertionError("Unexpected behaviour: true or exception were expected");
            }

            // register both socket channels for read
            forwardingData.getClientSelectionKey().interestOps(OP_READ);
            selectionKey.interestOps(OP_READ);

        } catch (IOException e) {
            // unable to connect to the specified host
            selectionKey.cancel();
            forwardingData.getClientSelectionKey().cancel();

            forwardingData.getClientSocketChannel().close();
        }
    }

    private void handleRead(SelectionKey selectionKey) throws IOException {
        SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        SocketChannelRef socketChannelRef = (SocketChannelRef) selectionKey.attachment();
        ForwardingData forwardingData = socketChannelRef.getForwardingData();

        switch (socketChannelRef.getSocketChannelSide()) {
            case CLIENT: {
                long recvNum = socketChannel.read(forwardingData.getClientToDestBuffer());

                if (recvNum == -1) { // socket was closed by client.
                    selectionKey.cancel();
                    forwardingData.getDestSelectionKey().cancel();

                    socketChannel.close();
                    forwardingData.getDestSocketChannel().close();
                }

                // there is no empty space to read something
                if (forwardingData.getClientToDestBuffer().remaining() == 0) {
                    selectionKey.interestOps(selectionKey.interestOps() & ~OP_READ);
                }

                // if we have read something, we can write it to another socket channel
                if (recvNum > 0) {
                    forwardingData.getDestSelectionKey().interestOps(
                            forwardingData.getDestSelectionKey().interestOps() | OP_WRITE);
                }
            }
            break;
            case DESTINATION: {

            }
            break;
            default:
                throw new AssertionError("Unexpected socket channel side received");
        }
    }

    private void handleWrite(SelectionKey selectionKey) throws IOException {
    }
}

