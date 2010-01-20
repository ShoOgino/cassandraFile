package org.apache.cassandra.net;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOError;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;

import org.apache.log4j.Logger;

public class IncomingTcpConnection extends Thread
{
    private static Logger logger = Logger.getLogger(IncomingTcpConnection.class);

    private final DataInputStream input;
    private final byte[] protocolBytes = new byte[MessagingService.PROTOCOL_SIZE];
    private final byte[] headerBytes = new byte[4];
    private final byte[] sizeBytes = new byte[4];
    private final ByteBuffer sizeBuffer = ByteBuffer.wrap(sizeBytes).asReadOnlyBuffer();

    public IncomingTcpConnection(Socket socket)
    {
        try
        {
            input = new DataInputStream(socket.getInputStream());
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
    }

    @Override
    public void run()
    {
        while (true)
        {
            try
            {
                input.readFully(protocolBytes);
                MessagingService.validateProtocol(protocolBytes);
                input.readFully(headerBytes);
                input.readFully(sizeBytes);
                int size = sizeBuffer.getInt();
                sizeBuffer.clear();
                byte[] contentBytes = new byte[size];
                input.readFully(contentBytes);
                MessagingService.getDeserializationExecutor().submit(new MessageDeserializationTask(new ByteArrayInputStream(contentBytes)));
            }
            catch (IOException e)
            {
                if (logger.isDebugEnabled())
                    logger.debug("error reading from socket; closing", e);
                break;
            }
        }
    }
}
