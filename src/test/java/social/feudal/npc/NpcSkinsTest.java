package social.feudal.npc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcSkinsTest {
    @Test void advertisedPackContainsTheFourOriginalClassicSkins()throws Exception{
        Path archive=Path.of("examples/imperial-atlas/feudal-people-v1.zip");
        assertEquals(NpcSkins.SHA1,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(archive))));
        var unique=new HashSet<String>();
        try(var zip=new ZipFile(archive.toFile())){
            for(String role:List.of("granary","freight","steward","chaplain")){
                var entry=zip.getEntry("assets/feudal/textures/entity/"+role+".png");assertNotNull(entry);
                var source=Path.of("assets/npc",role+".png");
                try(var in=zip.getInputStream(entry)){assertArrayEquals(Files.readAllBytes(source),in.readAllBytes());}
                var pixels=ImageIO.read(source.toFile());
                assertEquals(64,pixels.getWidth());assertEquals(64,pixels.getHeight());
                unique.add(Base64.getEncoder().encodeToString(Files.readAllBytes(source)));
            }
        }
        assertEquals(4,unique.size());
    }
}
