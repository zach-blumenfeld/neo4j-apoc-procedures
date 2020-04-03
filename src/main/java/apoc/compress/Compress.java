package apoc.compress;

import apoc.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Compress {

    private static final int BUFFER_SIZE = 1024 * 10;
    private static final int MULTIPLIER = 5;

    @UserFunction("apoc.gzip.unzip")
    @Description("apoc.gzip.unzip(bytes) returns a string representation of the compressed data")
    public String decompress(@Name("bytes") byte[] bytes) throws IOException {
        Reader stream = new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(bytes), BUFFER_SIZE), "utf-8");
        StringBuilder sb = new StringBuilder(bytes.length * MULTIPLIER);
        char[] buffer = new char[BUFFER_SIZE];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            sb.append(buffer, 0, read);
        }
        return sb.toString();
    }

    @UserFunction("apoc.gzip.zip")
    @Description("apoc.gzip.zip(string) returns a compressed byte array of the string")
    public byte[] compress(@Name("text") String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() / MULTIPLIER);
        try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(out, BUFFER_SIZE))) {
            writer.write(text);
        }
        return out.toByteArray();
    }
}
