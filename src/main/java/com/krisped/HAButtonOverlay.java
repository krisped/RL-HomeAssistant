package com.krisped;

import java.awt.*;
import java.awt.Dimension;
import lombok.RequiredArgsConstructor;
import net.runelite.client.ui.overlay.*;

/** Viser «HA Button Check: ON/OFF» øverst til venstre. */
@RequiredArgsConstructor
public class HAButtonOverlay extends Overlay
{
    private final java.util.function.Supplier<Boolean> state;

    @Override
    public Dimension render(Graphics2D g)
    {
        String txt = "HA Button Check: " + (state.get() ? "ON" : "OFF");
        g.setColor(Color.WHITE);
        g.drawString(txt, 10, 20);
        return null;
    }
}
