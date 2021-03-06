package mrtjp.projectred.integration;

import static codechicken.lib.vec.Rotation.sideRotations;
import static codechicken.lib.vec.Vector3.center;

import java.util.Arrays;
import java.util.Random;

import mrtjp.projectred.ProjectRedIntegration;
import mrtjp.projectred.ProjectRedIntegration;
import mrtjp.projectred.core.BasicUtils;
import mrtjp.projectred.integration.GateLogic.WorldStateBound;
import mrtjp.projectred.transmission.BasicWireUtils;
import mrtjp.projectred.transmission.IBundledEmitter;
import mrtjp.projectred.transmission.IConnectable;
import mrtjp.projectred.transmission.IWirePart;
import mrtjp.projectred.transmission.RedwirePart;
import mrtjp.projectred.transmission.WirePart;
import net.minecraft.block.Block;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.util.Icon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.ForgeDirection;
import codechicken.lib.data.MCDataInput;
import codechicken.lib.data.MCDataOutput;
import codechicken.lib.lighting.LazyLightMatrix;
import codechicken.lib.raytracer.IndexedCuboid6;
import codechicken.lib.render.EntityDigIconFX;
import codechicken.lib.vec.BlockCoord;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Rotation;
import codechicken.lib.vec.Translation;
import codechicken.lib.vec.Vector3;
import codechicken.multipart.IFaceRedstonePart;
import codechicken.multipart.IRandomDisplayTick;
import codechicken.multipart.JCuboidPart;
import codechicken.multipart.JNormalOcclusion;
import codechicken.multipart.NormalOcclusionTest;
import codechicken.multipart.RedstoneInteractions;
import codechicken.multipart.TFacePart;
import codechicken.multipart.TMultiPart;
import codechicken.multipart.TileMultipart;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class GatePart extends JCuboidPart implements TFacePart, IBundledEmitter, IConnectable, IFaceRedstonePart, JNormalOcclusion, IRandomDisplayTick {
    private EnumGate type;

    /** Server-side logic for gate **/
    private GateLogic logic;

    /**
     * The inside face for the containing block that its sitting on. (0 for
     * sitting on top of a block.)
     */
    private byte side;

    /** The absolute ForgeDirection that the front of the gate is facing. **/
    private byte front;

    private boolean requiresTickUpdate;
    private GateLogic.WithPointer pointer;
    private boolean hasBundledConnections;

    float pointerPos; // rendering only
    float pointerSpeed; // rendering only

    private int gateSettings;
    private short[] inputs = new short[4];
    private short[] outputs = new short[4];
    private int renderState;
    private int prevRenderState;
    private boolean isFirstTick = true;
    private boolean updateNextTick = false;
    private static boolean nextUpdateIsFromSelf = false;

    public GatePart(EnumGate type) {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        this.type = type;
        this.front = (byte) front;
        createLogic();
    }

    public void setupPlacement(EntityPlayer p, int side) {
        this.side = (byte) (side ^ 1);
        front = (byte) (Rotation.rotateSide(side ^ 1, Rotation.getSidedRotation(p, side)) ^ 1);
    }

    public int getSide() {
        return side;
    }

    public int getFront() {
        return front;
    }

    public EnumGate getGateType() {
        return type;
    }

    public GateLogic getLogic() {
        return logic;
    }

    public int getRenderState() {
        return prevRenderState;
    }

    @Override
    public void writeDesc(MCDataOutput packet) {
        if (type == null) {
            return;
        }
        NBTTagCompound p = new NBTTagCompound();
        p.setByte("t", (byte) type.ordinal());
        p.setByte("s", side);
        p.setByte("f", front);
        p.setShort("r", (short) prevRenderState);
        if (pointer != null) {
            p.setShort("p", (short) pointer.getPointerPosition());
            p.setFloat("P", pointer.getPointerSpeed());
        }
        packet.writeNBTTagCompound(p);
        if (!isFirstTick && getLogic() instanceof GateLogic.WithGui) {
            ((GateLogic.WithGui) getLogic()).updateWatchers(this);
        }
    }

    @Override
    public void readDesc(MCDataInput packet) {
        NBTTagCompound nbt = packet.readNBTTagCompound();
        type = EnumGate.VALID_GATES[nbt.getByte("t")];
        side = nbt.getByte("s");
        front = nbt.getByte("f");
        prevRenderState = nbt.getShort("r") & 0xFFFFF;
        if (nbt.hasKey("p")) {
            pointerPos = nbt.getShort("p");
            pointerSpeed = nbt.getFloat("P");
        }
        updateNextTick = true;
    }

    @Override
    public void save(NBTTagCompound tag) {
        super.save(tag);

        tag.setByte("type", type == null ? -1 : (byte) type.ordinal());
        tag.setByte("side", side);
        tag.setByte("front", front);

        tag.setLong("outputs", toBitfield16(outputs));
        tag.setLong("inputs", toBitfield16(inputs));

        tag.setShort("renderState", (short) renderState);
        tag.setShort("prevRenderState", (short) prevRenderState);
        tag.setShort("gateSettings", (short) gateSettings);
        if (logic != null && requiresTickUpdate) {
            NBTTagCompound tag2 = new NBTTagCompound();
            logic.write(tag2);
            tag.setTag("logic", tag2);
        }
    }

    @Override
    public void load(NBTTagCompound tag) {
        super.load(tag);
        try {
            type = EnumGate.VALID_GATES[tag.getByte("type")];
        } catch (Exception e) {
            type = EnumGate.AND; // shouldn't happen
        }
        side = tag.getByte("side");
        front = tag.getByte("front");

        renderState = tag.getShort("renderState") & 0xFFFF;
        prevRenderState = tag.getShort("prevRenderState") & 0xFFFF;

        gateSettings = tag.getShort("gateSettings") & 0xFFFF;

        if (tag.getTag("inputs") instanceof NBTTagLong) {
            fromBitfield16(tag.getLong("inputs"), inputs);
            fromBitfield16(tag.getLong("outputs"), outputs);
        }

        createLogic();

        if (logic != null && tag.hasKey("logic")) {
            logic.read(tag.getCompoundTag("logic"));
        }
    }

    public short[] computeInputs() {
        short[] newInputs = new short[4];
        for (int i = 0; i < 4; i++) {
            if (hasBundledConnections && ((GateLogic.WithBundledConnections) logic).connectsToBundled(i)) {
                newInputs[i] = getBundledInputBitmask(Rotator.relativeToAbsolute(side, front, i));
            } else {
                newInputs[i] = (short) RedstoneInteractions.getPowerTo(this, Rotator.relativeToAbsolute(side, front, i));
            }
        }
        return newInputs;
    }

    public void updateLogic(boolean fromTick, boolean forceUpdate) {
        if (type == null) {
            return;
        }

        inputs = computeInputs();

        updateRenderState();
        if (forceUpdate || fromTick == requiresTickUpdate) {
            short[] oldOutputs = outputs.clone();
            logic.computeOutFromIn(inputs, outputs, gateSettings);
            if (forceUpdate || !Arrays.equals(outputs, oldOutputs)) {
                updateNextTick = true;
            }
        }
    }

    private void updateRenderState() {
        renderState = logic.getRenderState(inputs, outputs, gateSettings);
        if (prevRenderState != renderState) {
            prevRenderState = renderState;
            updateNextTick = true;
        }
    }

    @Override
    public void update() {
        if (updateNextTick) {
            updateNextTick = false;
            updateChange();
        }
        if (BasicUtils.isServer(world())) {
            if (requiresTickUpdate) {
                updateLogic(true, false);
                if (logic instanceof WorldStateBound) {
                    if (((WorldStateBound) logic).needsWorldInfo()) {
                        ((WorldStateBound) logic).setWorldInfo(world(), x(), y(), z());
                    }
                }
            } else if (isFirstTick) {
                updateLogic(false, false);
            }
        } else {
            pointerPos += pointerSpeed;
        }
        isFirstTick = false;
    }

    @Override
    public void onNeighborChanged() {
        if (nextUpdateIsFromSelf) {
            return;
        }
        checkSupport();
        updateLogic(false, false);
    }

    public void updateChange() {
        tile().markDirty();
        tile().notifyPartChange(this);
        tile().markRender();
        nextUpdateIsFromSelf = true;
        notifyExtendedNeighbors();
        nextUpdateIsFromSelf = false;
        if (BasicUtils.isServer(world())) {
            sendDescUpdate();
        }
    }

    /**
     * Notifies neighbours one or two blocks away, in the same pattern as most
     * redstone updates.
     */
    public void notifyExtendedNeighbors() {
        world().notifyBlocksOfNeighborChange(x(), y(), z(), tile().getBlockType().blockID);
        world().notifyBlocksOfNeighborChange(x() + 1, y(), z(), tile().getBlockType().blockID);
        world().notifyBlocksOfNeighborChange(x() - 1, y(), z(), tile().getBlockType().blockID);
        world().notifyBlocksOfNeighborChange(x(), y() + 1, z(), tile().getBlockType().blockID);
        world().notifyBlocksOfNeighborChange(x(), y() - 1, z(), tile().getBlockType().blockID);
        world().notifyBlocksOfNeighborChange(x(), y(), z() + 1, tile().getBlockType().blockID);
        world().notifyBlocksOfNeighborChange(x(), y(), z() - 1, tile().getBlockType().blockID);
    }

    /**
     * See if the gate is still attached to something.
     */
    public void checkSupport() {
        if (BasicUtils.isClient(world())) {
            return;
        }
        BlockCoord localCoord = new BlockCoord(x(), y(), z());
        localCoord.offset(side);
        Block supporter = Block.blocksList[world().getBlockId(localCoord.x, localCoord.y, localCoord.z)];
        if (!BasicWireUtils.canPlaceWireOnSide(world(), localCoord.x, localCoord.y, localCoord.z, ForgeDirection.VALID_DIRECTIONS[side ^ 1], false)) {
            BasicUtils.dropItemFromLocation(world(), getItem(), false, null, getSide(), 10, new BlockCoord(x(), y(), z()));
            tile().remPart(this);
        }
    }

    private long toBitfield8(short[] a) {
        if (a.length > 8)
            throw new IllegalArgumentException("array too long");
        long rv = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] < 0 || a[k] > 255) {
                throw new IllegalArgumentException("element out of range (index " + k + ", value " + a[k] + ")");
            }
            rv = (rv << 8) | a[k];
        }
        return rv;
    }

    private long toBitfield16(short[] a) {
        if (a.length > 4) {
            throw new IllegalArgumentException("array too long");
        }
        long rv = 0;
        for (int k = 0; k < a.length; k++)
            rv = (rv << 16) | a[k];
        return rv;
    }

    private void fromBitfield8(long bf, short[] a) {
        if (a.length > 8) {
            throw new IllegalArgumentException("array too long");
        }
        for (int k = a.length - 1; k >= 0; k--) {
            a[k] = (short) (bf & 255);
            bf >>= 8;
        }
    }

    private void fromBitfield16(long bf, short[] a) {
        if (a.length > 4) {
            throw new IllegalArgumentException("array too long");
        }
        for (int k = a.length - 1; k >= 0; k--) {
            a[k] = (short) bf;
            bf >>= 16;
        }
    }

    private void createLogic() {
        logic = type.createLogic();
        requiresTickUpdate = !(logic instanceof GateLogic.Stateless);
        if (logic instanceof GateLogic.WithPointer) {
            pointer = (GateLogic.WithPointer) logic;
        } else {
            pointer = null;
        }
        hasBundledConnections = logic instanceof GateLogic.WithBundledConnections;
    }

    // called when shift-clicked by a screwdriver
    public void configure() {
        if (BasicUtils.isServer(world())) {
            gateSettings = logic.configure(gateSettings);
            updateLogic(false, true);
            updateNextTick = true;
        }
    }

    // called when non-shift-clicked by a screwdriver
    public void rotate() {
        int relativeRotationIndex = Rotation.rotationTo(side, front);
        relativeRotationIndex++;
        if (relativeRotationIndex > 3) {
            relativeRotationIndex = 0;
        }
        front = (byte) (Rotation.rotateSide(side, relativeRotationIndex));
        if (BasicUtils.isServer(world())) {
            updateLogic(false, true);
            updateNextTick = true;
        }
    }

    private short getBundledInputBitmask(int abs) {
        ForgeDirection fd = ForgeDirection.VALID_DIRECTIONS[abs];
        int x = x() + fd.offsetX;
        int y = y() + fd.offsetY;
        int z = z() + fd.offsetZ;
        TileMultipart tmp = BasicUtils.getTileEntity(world(), new BlockCoord(x, y, z), TileMultipart.class);
        if (tmp != null) {
            TMultiPart te = tmp.partMap(side);
            if (te instanceof IBundledEmitter) {
                byte[] values = ((IBundledEmitter) te).getBundledSignal(-50);//TODO
                if (values == null) {
                    return 0;
                }
                short rv = 0;
                // Turn array into bit mask
                for (int k = 15; k >= 0; k--) {
                    rv <<= 1;
                    if (values[k] != 0) {
                        rv |= 1;
                    }
                }
                return rv;
            }
        }
        return 0;
    }

    @Override
    public byte[] getBundledSignal(int side) {//TODO
        /*if (!hasBundledConnections) {
            return null;
        }

        if (blockFace != side) {
            return null;
        }

        int rel = Rotator.absoluteToRelative(side, front, toDirection);
        if (rel < 0) {
            return null;
        }

        if (!((GateLogic.WithBundledConnections) logic).connectsToBundled(rel)) {
            return null;
        }

        byte[] bundledOut = new byte[16];

        short bitmask = outputs[rel];
        for (int k = 0; k < 16; k++) {
            bundledOut[k] = ((bitmask & 1) != 0) ? (byte) 255 : 0;
            bitmask >>= 1;
        }

        return returnedBundledCableStrength;*/
        return null;
    }

    /** START TILEMULTIPART INTERACTIONS **/
    @Override
    public float getStrength(MovingObjectPosition hit, EntityPlayer player) {
        return hit.sideHit == 1 ? 1.75f : 1.5f;
    }

    public ItemStack getItem() {
        return new ItemStack(ProjectRedIntegration.itemPartGate, 1, type.ordinal());
    }

    @Override
    public Iterable<ItemStack> getDrops() {
        return Arrays.asList(getItem());
    }

    @Override
    public ItemStack pickItem(MovingObjectPosition hit) {
        return getItem();
    }

    @Override
    public Cuboid6 getBounds() {
        Cuboid6 base = new Cuboid6(0 / 8D, 0, 0 / 8D, 8 / 8D, 1 / 8D, 8 / 8D);
        return base.transform(sideRotations[side].at(center));
    }

    @Override
    public Iterable<Cuboid6> getCollisionBoxes() {
        return Arrays.asList();
    }

    @Override
    public String getType() {
        return getGateType().name;
    }

    @Override
    public int getSlotMask() {
        return 1 << side;
    }

    @Override
    public boolean solid(int side) {
        return false;
    }

    @Override
    public void onPartChanged(TMultiPart part) {
        checkSupport();
        if (BasicUtils.isClient(world()))
            return;
        updateLogic(false, false);
    }

    @Override
    public boolean activate(EntityPlayer player, MovingObjectPosition hit, ItemStack held) {
        if (held != null && held.getItem() == ProjectRedIntegration.itemScrewdriver) {
            if (player.isSneaking()) {
                this.configure();
                return true;
            } else {
                this.rotate();
                return true;
            }
        }

        if (world().isRemote) {
            return type != null && GateLogic.WithRightClickAction.class.isAssignableFrom(type.getLogicClass());
        }
        if (logic instanceof GateLogic.WithRightClickAction) {
            ((GateLogic.WithRightClickAction) logic).onRightClick(player, this);
            return true;
        }
        return false;
    }

    @Override
    public int redstoneConductionMap() {
        return 0;
    }

    @Override
    public int strongPowerLevel(int sideOut) {
        try {
            int rel = Rotator.absoluteToRelative(side, front, sideOut);
            if (rel > -1) {
                return outputs[rel];
            } else {
                return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public int weakPowerLevel(int side) {
        return strongPowerLevel(side);
    }

    @Override
    public boolean canConnectRedstone(int abs) {
        if (type == null) {
            return false;
        }
        if (getLogic() != null) {
            return getLogic().connectsToWire(Rotator.absoluteToRelative(side, front, abs));
        }
        GateLogic l = type.createLogic();
        return l.connectsToWire(Rotator.absoluteToRelative(side, front, abs));
    }

    @Override
    public int getFace() {
        return side;
    }

    /**
     * Collision bounding boxes.
     */
    @Override
    public Iterable<Cuboid6> getOcclusionBoxes() {
        return Arrays.asList(getBounds());
    }

    /**
     * Selection bounding box.
     */
    @Override
    public Iterable<IndexedCuboid6> getSubParts() {
        return Arrays.asList(new IndexedCuboid6(0, getBounds()));
    }

    /**
     * Part interaction bounding box.
     */
    @Override
    public boolean occlusionTest(TMultiPart npart) {
        return NormalOcclusionTest.apply(this, npart);
    }

    /** END TILEMULTIPART INTERACTIONS **/

    /** START RENDERSTUFF **/
    @Override
    public int getLightValue() {
        if (type != null) {
            GateRenderBridge render = type.createRenderBridge();
            int on = 0;
            if (render != null) {
                on += render.torchState.length;
                on += render.pointerX.length;
            }
            return on > 0 ? on + 4 : 0;
        }
        return 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderStatic(Vector3 pos, LazyLightMatrix olm, int pass) {
        if (pass == 0)
            GateStaticRenderer.instance.renderWorldBlock(this, (int) pos.x, (int) pos.y, (int) pos.z);
    }

    @Override
    public void renderDynamic(Vector3 pos, float frame, int pass) {
        if (pass == 0)
            GateDynamicRenderer.instance.renderGateWithTESR(this, pos.x, pos.y, pos.z);
    }

    @Override
    public void addDestroyEffects(EffectRenderer e) {
        EntityDigIconFX.addBlockDestroyEffects(world(), getBounds().add(Vector3.fromTileEntity(tile())), new Icon[] { GateRenderBridge._modelBase.getIcon() }, e);
    }

    @Override
    public void randomDisplayTick(Random ran) {
        GateRenderBridge bridge = type.createRenderBridge();
        bridge.set(getRenderState());
        for (int i = 0; i < bridge.torchState.length; i++) {
            if (!bridge.torchState[i]) {
                continue;
            }
            float xOffset = ((16f - bridge.torchX[i]) / 16f);
            float yOffset = (bridge.torchY[i] + 6) / 16f;
            float zOffset = ((16f - bridge.torchZ[i]) / 16f);
            Vector3 vec = new Vector3();
            vec.apply(new Translation(xOffset, yOffset, zOffset));
            vec.apply(Rotation.sideOrientation(getSide(), Rotation.rotationTo(getSide(), getFront())).at(Vector3.center));
            vec.apply(new Translation(x(), y(), z()));
            double d0 = (double) ((float) vec.x) + (double) (ran.nextFloat() - .5F) * 0.02D;
            double d1 = (double) ((float) vec.y) + (double) (ran.nextFloat() - .3F) * 0.2D;
            double d2 = (double) ((float) vec.z) + (double) (ran.nextFloat() - .5F) * 0.02D;
            world().spawnParticle("reddust", d0, d1, d2, 0.0D, 0.0D, 0.0D);
        }
        for (int i = 0; i < bridge.pointerX.length; i++) {
            float xOffset = ((16f - bridge.pointerX[i]) / 16f);
            float yOffset = (bridge.pointerY[i] + 6) / 16f;
            float zOffset = ((16f - bridge.pointerZ[i]) / 16f);
            Vector3 vec = new Vector3();
            vec.apply(new Translation(xOffset, yOffset, zOffset));
            vec.apply(Rotation.sideOrientation(getSide(), Rotation.rotationTo(getSide(), getFront())).at(Vector3.center));
            vec.apply(new Translation(x(), y(), z()));
            double d0 = (double) ((float) vec.x) + (double) (ran.nextFloat() - .5F) * 0.02D;
            double d1 = (double) ((float) vec.y) + (double) (ran.nextFloat() - .3F) * 0.2D;
            double d2 = (double) ((float) vec.z) + (double) (ran.nextFloat() - .5F) * 0.02D;
            world().spawnParticle("reddust", d0, d1, d2, 0.0D, 0.0D, 0.0D);
        }
    }

    /** END RENDERSTUFF **/

    @Override
    public boolean connectStraight(IWirePart wire, int side) {
        /*if (wire instanceof BundledCablePart) {
            if (hasBundledConnections && ((GateLogic.WithBundledConnections) logic).isBundledConnection(Rotator.absoluteToRelative(side, front, fromDirection^1))) {
                return true;
            }
        }*/
        return wire instanceof RedwirePart && getLogic().connectsToWire(side);
    }
    
    @Override
    public boolean connectCorner(WirePart wire, int side) {
        return connectStraight(wire, side);
    }
    
    @Override
    public boolean connectInternal(IWirePart wire, int side) {
        return connectStraight(wire, side);
    }
}
