package com.minermarmot.client.input;

import com.minermarmot.client.features.mining.MiningManager;
import com.minermarmot.client.features.tnt.TntManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

public final class AutomationInput extends KeyboardInput {
    private final KeyboardInput original;

    private AutomationInput(Minecraft client, KeyboardInput original) {
        super(client.options);
        this.original = original;
        keyPresses = original.keyPresses;
        moveVector = original.getMoveVector();
    }

    public static void install(Minecraft client) {
        if (client.player == null) return;
        boolean active = TntManager.controlsMovement() || MiningManager.controlsMovement();
        if (client.player.input instanceof AutomationInput input) {
            if (!active) client.player.input = input.original;
        } else if (active && client.player.input instanceof KeyboardInput input) {
            client.player.input = new AutomationInput(client, input);
        }
    }

    @Override public void tick() {
        original.tick();
        keyPresses = original.keyPresses;
        moveVector = original.getMoveVector();
        if (TntManager.controlsMovement()) {
            keyPresses = new Input(false, TntManager.retreats(), false, false, false, TntManager.sneaks(), false);
            moveVector = TntManager.movementInput();
        } else if (MiningManager.controlsMovement()) {
            boolean move = MiningManager.shouldMove();
            keyPresses = new Input(move, false, false, false, keyPresses.jump(), keyPresses.shift(), move);
            moveVector = move ? new Vec2(0, 1) : Vec2.ZERO;
        }
    }
}
