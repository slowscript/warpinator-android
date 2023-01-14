package slowscript.warpinator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class ZlibCompressor {
    private static final int BUFFER_SIZE = 1024;

    public static byte[] compress(final byte[] input, int length, int level) throws IOException
    {
        final Deflater deflater = new Deflater();
        deflater.setLevel(level);
        deflater.setInput(input, 0, length);
        deflater.finish();

        try (final ByteArrayOutputStream output = new ByteArrayOutputStream(length))
        {
            final byte[] buffer = new byte[1024];
            while (!deflater.finished())
            {
                final int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }

            return output.toByteArray();
        }
    }

    public static byte[] decompress(final byte[] input) throws Exception
    {
        final Inflater inflater = new Inflater();
        inflater.setInput(input);

        try (final ByteArrayOutputStream output = new ByteArrayOutputStream(input.length))
        {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (!inflater.finished())
            {
                final int count = inflater.inflate(buffer);
                output.write(buffer, 0, count);
            }

            return output.toByteArray();
        }
    }
}
