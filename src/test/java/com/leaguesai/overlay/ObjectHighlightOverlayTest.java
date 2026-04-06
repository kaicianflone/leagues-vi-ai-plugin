package com.leaguesai.overlay;

import com.leaguesai.LeaguesAiConfig;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import org.junit.Before;
import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ObjectHighlightOverlayTest {

    private Client client;
    private LeaguesAiConfig config;
    private ObjectHighlightOverlay overlay;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(LeaguesAiConfig.class);
        when(config.overlayColor()).thenReturn(Color.MAGENTA);
        // Default: scene is null so setTargetObjectIds doesn't try to walk
        when(client.getScene()).thenReturn(null);
        overlay = new ObjectHighlightOverlay(client, config);
    }

    @Test
    public void render_withNoTarget_doesNotTouchGraphics() {
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setAndClear_objectIds() {
        overlay.setTargetObjectIds(Arrays.asList(100, 200));
        assertEquals(new HashSet<>(Arrays.asList(100, 200)), overlay.getTargetObjectIds());
        overlay.clear();
        assertTrue(overlay.getTargetObjectIds().isEmpty());
    }

    @Test
    public void render_whenCacheEmpty_doesNotDraw() {
        overlay.setTargetObjectIds(Collections.singletonList(100));
        // Scene is null in setUp() so cache rebuild yields nothing
        Graphics2D g = mock(Graphics2D.class);
        assertNull(overlay.render(g));
        verifyNoInteractions(g);
    }

    @Test
    public void setTargetObjectIds_withMatchingSceneObject_populatesCacheAndRenders() {
        Scene scene = mock(Scene.class);
        Tile[][][] tiles = new Tile[1][104][104];
        Tile tile = mock(Tile.class);
        tiles[0][50][50] = tile;

        GameObject obj = mock(GameObject.class);
        when(obj.getId()).thenReturn(555);
        Polygon hull = new Polygon(new int[]{0, 10, 10, 0}, new int[]{0, 0, 10, 10}, 4);
        when(obj.getConvexHull()).thenReturn(hull);
        when(tile.getGameObjects()).thenReturn(new GameObject[]{obj});

        when(client.getScene()).thenReturn(scene);
        when(scene.getTiles()).thenReturn(tiles);

        overlay.setTargetObjectIds(Collections.singletonList(555));

        List<GameObject> cache = overlay.getMatchingObjectsForTest();
        assertEquals(1, cache.size());

        BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = spy(img.createGraphics());
        assertNull(overlay.render(g));

        verify(g, atLeastOnce()).fill(any(Shape.class));
        verify(g, atLeastOnce()).draw(any(Shape.class));
        g.dispose();
    }

    @Test
    public void onGameObjectSpawned_addsMatchingObjectToCache() {
        overlay.setTargetObjectIds(Collections.singletonList(777));
        assertTrue(overlay.getMatchingObjectsForTest().isEmpty());

        GameObject obj = mock(GameObject.class);
        when(obj.getId()).thenReturn(777);
        GameObjectSpawned event = mock(GameObjectSpawned.class);
        when(event.getGameObject()).thenReturn(obj);

        overlay.onGameObjectSpawned(event);
        assertEquals(1, overlay.getMatchingObjectsForTest().size());
    }

    @Test
    public void onGameObjectSpawned_ignoresNonMatching() {
        overlay.setTargetObjectIds(Collections.singletonList(777));
        GameObject obj = mock(GameObject.class);
        when(obj.getId()).thenReturn(123);
        GameObjectSpawned event = mock(GameObjectSpawned.class);
        when(event.getGameObject()).thenReturn(obj);

        overlay.onGameObjectSpawned(event);
        assertTrue(overlay.getMatchingObjectsForTest().isEmpty());
    }

    @Test
    public void onGameObjectDespawned_removesFromCache() {
        overlay.setTargetObjectIds(Collections.singletonList(777));
        GameObject obj = mock(GameObject.class);
        when(obj.getId()).thenReturn(777);

        GameObjectSpawned spawn = mock(GameObjectSpawned.class);
        when(spawn.getGameObject()).thenReturn(obj);
        overlay.onGameObjectSpawned(spawn);
        assertEquals(1, overlay.getMatchingObjectsForTest().size());

        GameObjectDespawned despawn = mock(GameObjectDespawned.class);
        when(despawn.getGameObject()).thenReturn(obj);
        overlay.onGameObjectDespawned(despawn);
        assertTrue(overlay.getMatchingObjectsForTest().isEmpty());
    }
}
