package pwn.noobs.trouserstreak.modules;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShearsItem;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import pwn.noobs.trouserstreak.Trouser;

import java.util.List;

public class SuperInstaMine extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgAutoTool = settings.createGroup("AutoTool");

    // General Settings
    private final Setting<listModes> listmode = sgGeneral.add(new EnumSetting.Builder<listModes>()
            .name("List Mode")
            .description("Whether to break or not break the block list.")
            .defaultValue(listModes.blacklist)
            .build());

    private final Setting<List<Block>> skippableBlox = sgGeneral.add(new BlockListSetting.Builder()
            .name("Blocks to Skip")
            .description("Skips instamining this block.")
            .visible(() -> listmode.get() == listModes.blacklist)
            .build());

    private final Setting<List<Block>> nonskippableBlox = sgGeneral.add(new BlockListSetting.Builder()
            .name("Blocks to Break")
            .description("Only instamine this block.")
            .visible(() -> listmode.get() == listModes.whitelist)
            .build());

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
            .name("Break Modes (Range)")
            .description("The range around the center block to break more blocks")
            .defaultValue(0)
            .sliderRange(-1, 7)
            .min(-1)
            .max(7)
            .build());

    private final Setting<Boolean> aorient = sgGeneral.add(new BoolSetting.Builder()
            .name("AutoOrientBreakDirection")
            .description("Automatically chooses whether to break upright or horizontal.")
            .defaultValue(true)
            .build());

    private final Setting<Modes> mode = sgGeneral.add(new EnumSetting.Builder<Modes>()
            .name("Break Direction Mode")
            .description("Choose whether to break upright or horizontal.")
            .defaultValue(Modes.Vertical)
            .visible(() -> !aorient.get())
            .build());

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("The delay between breaks.")
            .defaultValue(0)
            .min(0)
            .sliderMax(20)
            .build());

    private final Setting<Boolean> pick = sgGeneral.add(new BoolSetting.Builder()
            .name("only-pick")
            .description("Only tries to mine the block if you are holding a pickaxe.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-use")
            .description("Pauses mining while eating or drinking.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
            .name("Swing Hand")
            .description("Do or Do Not swing hand when instamining.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Faces the blocks being mined server side.")
            .defaultValue(true)
            .build());

    // AutoTool Settings (FITUR BARU)
    private final Setting<Boolean> autoTool = sgAutoTool.add(new BoolSetting.Builder()
            .name("auto-tool")
            .description("Automatically swaps to the best tool.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> silentSwitch = sgAutoTool.add(new BoolSetting.Builder()
            .name("silent-switch")
            .description("Swaps to the tool silently (client-side only visible).")
            .defaultValue(true)
            .visible(autoTool::get)
            .build());

    // Render Settings
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
            .name("render")
            .description("Renders a block overlay on the block being broken.")
            .defaultValue(true)
            .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
            .name("side-color")
            .description("The color of the sides of the blocks being rendered.")
            .defaultValue(new SettingColor(204, 0, 0, 10))
            .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
            .name("line-color")
            .description("The color of the lines of the blocks being rendered.")
            .defaultValue(new SettingColor(204, 0, 0, 255))
            .build());

    private int ticks;

    // Defines BlockPos Mutables
    private final BlockPos.Mutable[] bPos = new BlockPos.Mutable[27];

    private Direction direction;
    private Direction playermovingdirection;
    private int playerpitch;

    public SuperInstaMine() {
        super(Trouser.Main, "SuperInstaMine", "Attempts to instantly mine blocks. Modified to be able to break many blocks at a time.");
        // Initialize block positions to avoid NPE
        for (int i = 0; i < bPos.length; i++) {
            bPos[i] = new BlockPos.Mutable(0, -128, 0);
        }
    }

    @Override
    public void onActivate() {
        ticks = 0;
        for (BlockPos.Mutable pos : bPos) {
            pos.set(0, -128, 0);
        }
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (mc.player == null) return;
        direction = event.direction;
        playermovingdirection = mc.player.getMovementDirection();
        playerpitch = Math.round(mc.player.getPitch());
        
        // Mapping positions exactly like original to preserve logic
        // Center
        bPos[0].set(event.blockPos);
        
        // Middle layer 3x3x3
        bPos[1].set(event.blockPos.getX() + 1, event.blockPos.getY(), event.blockPos.getZ());
        bPos[2].set(event.blockPos.getX() - 1, event.blockPos.getY(), event.blockPos.getZ());
        bPos[3].set(event.blockPos.getX(), event.blockPos.getY(), event.blockPos.getZ() + 1);
        bPos[4].set(event.blockPos.getX(), event.blockPos.getY(), event.blockPos.getZ() - 1);
        bPos[5].set(event.blockPos.getX() + 1, event.blockPos.getY(), event.blockPos.getZ() + 1);
        bPos[6].set(event.blockPos.getX() - 1, event.blockPos.getY(), event.blockPos.getZ() - 1);
        bPos[7].set(event.blockPos.getX() + 1, event.blockPos.getY(), event.blockPos.getZ() - 1);
        bPos[8].set(event.blockPos.getX() - 1, event.blockPos.getY(), event.blockPos.getZ() + 1);
        
        // Top layer 3x3x3
        bPos[9].set(event.blockPos.getX(), event.blockPos.getY() + 1, event.blockPos.getZ());
        bPos[10].set(event.blockPos.getX() + 1, event.blockPos.getY() + 1, event.blockPos.getZ());
        bPos[11].set(event.blockPos.getX() - 1, event.blockPos.getY() + 1, event.blockPos.getZ());
        bPos[12].set(event.blockPos.getX(), event.blockPos.getY() + 1, event.blockPos.getZ() + 1);
        bPos[13].set(event.blockPos.getX(), event.blockPos.getY() + 1, event.blockPos.getZ() - 1);
        bPos[14].set(event.blockPos.getX() + 1, event.blockPos.getY() + 1, event.blockPos.getZ() + 1);
        bPos[15].set(event.blockPos.getX() - 1, event.blockPos.getY() + 1, event.blockPos.getZ() - 1);
        bPos[16].set(event.blockPos.getX() + 1, event.blockPos.getY() + 1, event.blockPos.getZ() - 1);
        bPos[17].set(event.blockPos.getX() - 1, event.blockPos.getY() + 1, event.blockPos.getZ() + 1);
        
        // Bottom layer 3x3
        bPos[18].set(event.blockPos.getX(), event.blockPos.getY() - 1, event.blockPos.getZ());
        bPos[19].set(event.blockPos.getX() + 1, event.blockPos.getY() - 1, event.blockPos.getZ());
        bPos[20].set(event.blockPos.getX() - 1, event.blockPos.getY() - 1, event.blockPos.getZ());
        bPos[21].set(event.blockPos.getX(), event.blockPos.getY() - 1, event.blockPos.getZ() + 1);
        bPos[22].set(event.blockPos.getX(), event.blockPos.getY() - 1, event.blockPos.getZ() - 1);
        bPos[23].set(event.blockPos.getX() + 1, event.blockPos.getY() - 1, event.blockPos.getZ() + 1);
        bPos[24].set(event.blockPos.getX() - 1, event.blockPos.getY() - 1, event.blockPos.getZ() - 1);
        bPos[25].set(event.blockPos.getX() + 1, event.blockPos.getY() - 1, event.blockPos.getZ() - 1);
        bPos[26].set(event.blockPos.getX() - 1, event.blockPos.getY() - 1, event.blockPos.getZ() + 1);
    }

    public static boolean isTool(ItemStack itemStack) {
        return itemStack.isIn(ItemTags.AXES) || itemStack.isIn(ItemTags.HOES) || itemStack.isIn(ItemTags.PICKAXES) || itemStack.isIn(ItemTags.SHOVELS) || itemStack.getItem() instanceof ShearsItem;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (ticks >= tickDelay.get()) {
            ticks = 0;
            if (shouldMine()) {
                executeMiningLogic();
            }
        } else {
            ticks++;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || !shouldMine() || mc.world == null || mc.player == null) return;
        executeRenderLogic(event);
    }

    // --- Core Logic Refactored ---
    // This looks at the logic from the original file and executes 'action' (Mine or Render)
    // Preservation of logic: The nested if statements and loops are flattened but follow the same condition path.

    private void executeMiningLogic() {
        int r = range.get();
        // Range -1 to 7 logic preservation
        
        // Base logic (Always checks center)
        tryMine(bPos[0]);

        if (r == -1) {
            switch (playermovingdirection) {
                case NORTH -> tryMine(bPos[2]);
                case SOUTH -> tryMine(bPos[1]);
                case EAST -> tryMine(bPos[4]);
                case WEST -> tryMine(bPos[3]);
            }
        }

        if (r >= 0 && r <= 7) {
            // Range 0 only does bPos[0] which is already done above.
        }

        if (r >= 1) {
             switch (playermovingdirection) {
                case NORTH -> tryMine(bPos[1]);
                case SOUTH -> tryMine(bPos[2]);
                case EAST -> tryMine(bPos[3]);
                case WEST -> tryMine(bPos[4]);
            }
        }

        if (r >= 2) {
            if (playermovingdirection == Direction.NORTH || playermovingdirection == Direction.SOUTH) {
                tryMine(bPos[2]);
                tryMine(bPos[1]);
            }
            if (playermovingdirection == Direction.EAST || playermovingdirection == Direction.WEST) {
                tryMine(bPos[4]);
                tryMine(bPos[3]);
            }
        }

        if (r >= 3) {
            boolean vert = (aorient.get() && playerpitch <= 30 && playerpitch >= -30) || (mode.get() == Modes.Vertical && !aorient.get());
            boolean horiz = (aorient.get() && (playerpitch > 30 || playerpitch < -30)) || (mode.get() == Modes.Horizontal && !aorient.get());

            if (vert) {
                if (playermovingdirection == Direction.NORTH || playermovingdirection == Direction.SOUTH) {
                    tryMine(bPos[2]);
                    tryMine(bPos[1]);
                    tryMine(bPos[9]);
                    tryMine(bPos[18]);
                }
                if (playermovingdirection == Direction.EAST || playermovingdirection == Direction.WEST) {
                    tryMine(bPos[4]);
                    tryMine(bPos[3]);
                    tryMine(bPos[9]);
                    tryMine(bPos[18]);
                }
            }
            if (horiz) {
                tryMine(bPos[1]);
                tryMine(bPos[2]);
                tryMine(bPos[3]);
                tryMine(bPos[4]);
            }
        }
        
        if (r >= 4) {
             boolean vert = (aorient.get() && playerpitch <= 30 && playerpitch >= -30) || (mode.get() == Modes.Vertical && !aorient.get());
             boolean horiz = (aorient.get() && (playerpitch > 30 || playerpitch < -30)) || (mode.get() == Modes.Horizontal && !aorient.get());

             if (vert) {
                if (playermovingdirection == Direction.NORTH || playermovingdirection == Direction.SOUTH) {
                    tryMine(bPos[2]); tryMine(bPos[1]); tryMine(bPos[9]); tryMine(bPos[18]);
                    tryMine(bPos[10]); tryMine(bPos[11]); tryMine(bPos[19]); tryMine(bPos[20]);
                }
                if (playermovingdirection == Direction.EAST || playermovingdirection == Direction.WEST) {
                    tryMine(bPos[4]); tryMine(bPos[3]); tryMine(bPos[9]); tryMine(bPos[18]);
                    tryMine(bPos[12]); tryMine(bPos[13]); tryMine(bPos[21]); tryMine(bPos[22]);
                }
             }
             if (horiz) {
                 for(int i=1; i<=8; i++) tryMine(bPos[i]);
             }
        }

        if (r >= 5) {
            // Range 5 iterates 1 to 9 and 18
            for(int i=1; i<=9; i++) tryMine(bPos[i]);
            tryMine(bPos[18]);
        }

        if (r >= 6) {
            // Range 6 iterates 1 to 22
            for(int i=1; i<=22; i++) tryMine(bPos[i]);
        }

        if (r == 7) {
            // Range 7 iterates 1 to 26
            for(int i=1; i<=26; i++) tryMine(bPos[i]);
        }
    }

    private void executeRenderLogic(Render3DEvent event) {
        // Reuse the exact same logic structure but call rendering
        // This duplication of structure is necessary to ensure Render matches Break exactly based on settings
        int r = range.get();
        
        tryRender(bPos[0], event); // Base

        if (r == -1) {
            switch (playermovingdirection) {
                case NORTH -> tryRender(bPos[2], event);
                case SOUTH -> tryRender(bPos[1], event);
                case EAST -> tryRender(bPos[4], event);
                case WEST -> tryRender(bPos[3], event);
            }
        }
        
        if (r >= 1) {
             switch (playermovingdirection) {
                case NORTH -> tryRender(bPos[1], event);
                case SOUTH -> tryRender(bPos[2], event);
                case EAST -> tryRender(bPos[3], event);
                case WEST -> tryRender(bPos[4], event);
            }
        }

        if (r >= 2) {
            if (playermovingdirection == Direction.NORTH || playermovingdirection == Direction.SOUTH) {
                tryRender(bPos[2], event); tryRender(bPos[1], event);
            }
            if (playermovingdirection == Direction.EAST || playermovingdirection == Direction.WEST) {
                tryRender(bPos[4], event); tryRender(bPos[3], event);
            }
        }
        
        if (r >= 3) {
            boolean vert = (aorient.get() && playerpitch <= 30 && playerpitch >= -30) || (mode.get() == Modes.Vertical && !aorient.get());
            boolean horiz = (aorient.get() && (playerpitch > 30 || playerpitch < -30)) || (mode.get() == Modes.Horizontal && !aorient.get());

             if (vert) {
                if (playermovingdirection == Direction.NORTH || playermovingdirection == Direction.SOUTH) {
                    tryRender(bPos[2], event); tryRender(bPos[1], event); tryRender(bPos[9], event); tryRender(bPos[18], event);
                }
                if (playermovingdirection == Direction.EAST || playermovingdirection == Direction.WEST) {
                    tryRender(bPos[4], event); tryRender(bPos[3], event); tryRender(bPos[9], event); tryRender(bPos[18], event);
                }
            }
            if (horiz) {
                tryRender(bPos[1], event); tryRender(bPos[2], event); tryRender(bPos[3], event); tryRender(bPos[4], event);
            }
        }
        
        if (r >= 4) {
             boolean vert = (aorient.get() && playerpitch <= 30 && playerpitch >= -30) || (mode.get() == Modes.Vertical && !aorient.get());
             boolean horiz = (aorient.get() && (playerpitch > 30 || playerpitch < -30)) || (mode.get() == Modes.Horizontal && !aorient.get());

             if (vert) {
                if (playermovingdirection == Direction.NORTH || playermovingdirection == Direction.SOUTH) {
                    tryRender(bPos[2], event); tryRender(bPos[1], event); tryRender(bPos[9], event); tryRender(bPos[18], event);
                    tryRender(bPos[10], event); tryRender(bPos[11], event); tryRender(bPos[19], event); tryRender(bPos[20], event);
                }
                if (playermovingdirection == Direction.EAST || playermovingdirection == Direction.WEST) {
                    tryRender(bPos[4], event); tryRender(bPos[3], event); tryRender(bPos[9], event); tryRender(bPos[18], event);
                    tryRender(bPos[12], event); tryRender(bPos[13], event); tryRender(bPos[21], event); tryRender(bPos[22], event);
                }
             }
             if (horiz) {
                 for(int i=1; i<=8; i++) tryRender(bPos[i], event);
             }
        }

        if (r >= 5) {
            for(int i=1; i<=9; i++) tryRender(bPos[i], event);
            tryRender(bPos[18], event);
        }
        if (r >= 6) {
            for(int i=1; i<=22; i++) tryRender(bPos[i], event);
        }
        if (r == 7) {
             for(int i=1; i<=26; i++) tryRender(bPos[i], event);
        }
    }

    // --- Helper Methods to reduce Spaghetti ---

    private boolean checkBlockConfig(BlockPos pos) {
        Block block = mc.world.getBlockState(pos).getBlock();
        if (listmode.get() == listModes.whitelist) {
            return nonskippableBlox.get().contains(block);
        } else {
            return !skippableBlox.get().contains(block);
        }
    }

    private void tryMine(BlockPos pos) {
        if (!checkBlockConfig(pos)) return;
        
        // AutoTool Logic
        if (autoTool.get() && !mc.player.getAbilities().creativeMode) {
             int bestSlot = InvUtils.findFastestTool(mc.player.getInventory(), mc.world.getBlockState(pos)).slot();
             if (bestSlot != -1 && bestSlot != mc.player.getInventory().selectedSlot) {
                 InvUtils.swap(bestSlot, !silentSwitch.get());
             }
        }

        // Logic check: Creative OR Not Tool required OR Holding correct tool
        boolean canBreak = (mc.player.getAbilities().creativeMode || !isTool(mc.player.getMainHandStack())) && BlockUtils.canBreak(pos);
        if (!canBreak && !mc.player.getMainHandStack().isSuitableFor(mc.world.getBlockState(pos))) return;
        if (!BlockUtils.canBreak(pos)) return;

        // The Exploit Packet Logic
        Runnable action = () -> {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, direction));
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, direction));
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), action);
        } else {
            action.run();
        }

        if (swing.get()) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private void tryRender(BlockPos pos, Render3DEvent event) {
        if (!checkBlockConfig(pos)) return;
        boolean canBreak = (mc.player.getAbilities().creativeMode || !isTool(mc.player.getMainHandStack())) && BlockUtils.canBreak(pos);
        if (!canBreak && !mc.player.getMainHandStack().isSuitableFor(mc.world.getBlockState(pos))) return;
        
        if (BlockUtils.canBreak(pos)) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    private boolean shouldMine() {
        assert mc.player != null;
        if (bPos[0].getY() == -128) return false;
        
        // Pause on use logic
        if (pauseOnUse.get() && (mc.player.isUsingItem() || mc.player.isEating() || mc.player.isDrinking())) return false;

        return !pick.get() || (mc.player.getMainHandStack().getItem() == Items.DIAMOND_PICKAXE || mc.player.getMainHandStack().getItem() == Items.NETHERITE_PICKAXE);
    }

    public enum Modes {
        Horizontal, Vertical
    }

    public enum listModes {
        whitelist, blacklist
    }
}
``` 🚀
