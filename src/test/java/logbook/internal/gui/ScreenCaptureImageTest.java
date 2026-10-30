package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Synthetic pixels only: never constructs Robot or reads the user's desktop. */
class ScreenCaptureImageTest {
    private BufferedImage image(int width,int height,Color color) {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();try {g.setColor(color);g.fillRect(0,0,width,height);}finally{g.dispose();}return image;
    }
    @Test void pngPreservesPixelsAndLegacyAlphaMarkerWithoutMutatingInput() throws Exception {
        var source=image(12,8,Color.BLUE);byte[] encoded=ScreenCapture.encode(source,"png");
        var restored=ImageIO.read(new ByteArrayInputStream(encoded));
        assertEquals(12,restored.getWidth());assertEquals(8,restored.getHeight());
        assertEquals(0xfe,restored.getRGB(0,0)>>>24);assertEquals(Color.BLUE.getRGB(),restored.getRGB(3,4));
        assertEquals(Color.BLUE.getRGB(),source.getRGB(0,0));
    }
    @Test void jpegProducesReadableDimensionsAndApproximateColor() throws Exception {
        var source=image(24,16,Color.RED);var restored=ImageIO.read(new ByteArrayInputStream(ScreenCapture.encode(source,"jpg")));
        assertEquals(24,restored.getWidth());assertEquals(16,restored.getHeight());
        var pixel=new Color(restored.getRGB(12,8));assertTrue(pixel.getRed()>245);assertTrue(pixel.getGreen()<10);assertTrue(pixel.getBlue()<10);
    }
    @Test void cutUsesGameCoordinatesAtNativeAndHalfScale() {
        var rect=new Rectangle(100,80,400,200);
        var nativeCut=ScreenCapture.cut(image(1200,720,Color.GREEN),rect);
        var halfCut=ScreenCapture.cut(image(600,360,Color.GREEN),rect);
        assertEquals(400,nativeCut.getWidth());assertEquals(200,nativeCut.getHeight());
        assertEquals(200,halfCut.getWidth());assertEquals(100,halfCut.getHeight());
        assertEquals(Color.GREEN.getRGB(),halfCut.getRGB(10,10));assertEquals(new Rectangle(100,80,400,200),rect);
    }
    @Test void tilesKeepOrderAndWhitePaddingAndRoundtrip() throws Exception {
        byte[] red=ScreenCapture.encode(image(8,6,Color.RED),"jpg"),blue=ScreenCapture.encode(image(8,6,Color.BLUE),"jpg");
        var tiled=ScreenCapture.tileImage(List.of(red,blue,red),2);
        assertEquals(16,tiled.getWidth());assertEquals(12,tiled.getHeight());
        assertTrue(new Color(tiled.getRGB(2,2)).getRed()>245);assertTrue(new Color(tiled.getRGB(10,2)).getBlue()>245);
        assertTrue(new Color(tiled.getRGB(2,8)).getRed()>245);assertEquals(Color.WHITE.getRGB(),tiled.getRGB(10,8));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(ScreenCapture.encode(tiled,"png"))));
    }
    @Test void emptyTilesProduceNoImage() throws Exception {assertNull(ScreenCapture.tileImage(List.of(),2));}
    @Test void syntheticWhiteFrameDetectsGameRectangleAndPlainImageDoesNot() {
        var source=image(620,380,Color.WHITE);var g=source.createGraphics();
        try {g.setColor(Color.BLUE);g.fillRect(10,10,600,360);}finally{g.dispose();}
        assertEquals(new Rectangle(10,10,600,360),ScreenCapture.detectGameScreen(source));
        assertNull(ScreenCapture.detectGameScreen(image(620,380,Color.WHITE)));
    }
}
