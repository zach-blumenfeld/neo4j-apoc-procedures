package apoc.compress;

import apoc.util.TestUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.*;

public class CompressTest {

    @Test
    public void decompress() throws IOException {
        StringBuilder sb = createTestData();
        Compress compress = new Compress();
        byte[] bytes = compress.compress(sb.toString());
        assertTrue(bytes.length > sb.length()/10);
        String result = compress.decompress(bytes);
        assertEquals(sb.toString(), result);
    }

    @NotNull
    private StringBuilder createTestData() {
        StringBuilder sb = new StringBuilder(1000);
        for (int i=0;i<100;i++) {
            sb.append("{id:"+i+",name:'A Name '"+i+",age:"+i%100+"}\n");
        }
        return sb.toString();
    }
}