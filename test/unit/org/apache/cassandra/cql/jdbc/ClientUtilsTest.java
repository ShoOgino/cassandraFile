package org.apache.cassandra.cql.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.UUID;

import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.UUIDGen;
import org.junit.Test;

public class ClientUtilsTest
{
    /** Exercises the classes in the clientutil jar to expose missing dependencies. */
    @Test
    public void test() throws UnknownHostException
    {
        JdbcAscii.instance.compose(wr("string"));
        JdbcBoolean.instance.compose(wr("false"));
        JdbcBytes.instance.compose(wr("string"));
        JdbcDate.instance.compose(ByteBufferUtil.bytes((new Date(System.currentTimeMillis())).getTime()));
        JdbcDecimal.instance.compose(decomposeBigDecimal(new BigDecimal(1)));
        JdbcDouble.instance.compose(ByteBufferUtil.bytes(1.0d));
        JdbcFloat.instance.compose(ByteBufferUtil.bytes(1.0f));
        JdbcInt32.instance.compose(ByteBufferUtil.bytes(1));
        JdbcInteger.instance.compose(ByteBuffer.wrap((new BigInteger("1")).toByteArray()));
        JdbcLong.instance.compose(ByteBufferUtil.bytes(1L));
        JdbcUTF8.instance.compose(wr("string"));

        // UUIDGen
        UUID uuid = UUIDGen.makeType1UUIDFromHost(InetAddress.getLocalHost());
        JdbcUUID.instance.compose(ByteBuffer.wrap(UUIDGen.decompose(uuid)));
        JdbcTimeUUID.instance.compose(ByteBuffer.wrap(UUIDGen.decompose(uuid)));
        JdbcLexicalUUID.instance.compose(ByteBuffer.wrap(UUIDGen.decompose(uuid)));

        // Raise a MarshalException
        try
        {
            JdbcLexicalUUID.instance.getString(ByteBuffer.wrap("notauuid".getBytes()));
        }
        catch (MarshalException me)
        {
            // Success
        }
    }

    /* Copypasta from DecimalType */
    private static ByteBuffer decomposeBigDecimal(BigDecimal value)
    {
        if (value == null) return ByteBufferUtil.EMPTY_BYTE_BUFFER;

        BigInteger bi = value.unscaledValue();
        Integer scale = value.scale();
        byte[] bibytes = bi.toByteArray();
        byte[] sbytes = ByteBufferUtil.bytes(scale).array();
        byte[] bytes = new byte[bi.toByteArray().length+4];

        for (int i = 0 ; i < 4 ; i++) bytes[i] = sbytes[i];
        for (int i = 4 ; i < bibytes.length+4 ; i++) bytes[i] = bibytes[i-4];

        return ByteBuffer.wrap(bytes);
    }

    private static ByteBuffer wr(String value)
    {
        return ByteBuffer.wrap(value.getBytes());
    }
}
