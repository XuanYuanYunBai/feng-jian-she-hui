package social.feudal.realm;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapLegendTest {
    @Test void chineseLabelsAndGrantTotalsFitTheMap() {
        for (String text : List.of("帝国疆域图","地方勘界图","1王城  2伯领","3主教驻地",
                "北上","25 x 25 区块","合计 256/256","授予 256")) {
            assertTrue(MapLegend.FONT.isValid(text),text);
            assertTrue(MapLegend.FONT.getWidth(text) <= 122,text);
        }
    }
}
