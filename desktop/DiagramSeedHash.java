import com.fasterxml.jackson.databind.ObjectMapper;
import kr.ictwork.diagram.DiagramScene;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Build-time helper: reuse the owning backend's exact canonical scene hash. */
public class DiagramSeedHash {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            String scene = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
            System.out.println(DiagramScene.Companion.stored(scene, mapper).getHash());
        }
    }
}
