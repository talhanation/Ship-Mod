package com.talhanation.smallships.entities;

import com.talhanation.smallships.Main;
import com.talhanation.smallships.config.SmallShipsConfig;
import com.talhanation.smallships.init.SoundInit;
import com.talhanation.smallships.network.MessageSailState;
import net.minecraft.entity.*;
import net.minecraft.entity.passive.WaterMobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.network.NetworkHooks;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.List;

public abstract class AbstractCogEntity extends TNBoatEntity {
    private static final DataParameter<Boolean> SAIL_STATE = EntityDataManager.createKey(AbstractCogEntity.class, DataSerializers.BOOLEAN);
    public float momentum;
    public float outOfControlTicks;
    public float deltaRotation;
    public boolean removed;
    private double waterLevel;
    public boolean leftInputDown;
    public boolean rightInputDown;
    public boolean forwardInputDown;
    public boolean backInputDown;
    private float boatGlide;
    private Status status;
    private Status previousStatus;
    public ItemStackHandler inventory = initInventory();
    public int passengerwaittime;
    public float passengerfaktor;
    public boolean leftsteer;
    public boolean rightsteer;

    protected abstract ItemStackHandler initInventory();

    private LazyOptional<ItemStackHandler> itemHandler = LazyOptional.of(() -> this.inventory);

    public AbstractCogEntity(EntityType<? extends AbstractCogEntity> entityType, World worldIn) {
        super(entityType, worldIn);
    }

    protected void registerData() {
        super.registerData();
        this.dataManager.register(SAIL_STATE, false);
    }

    public boolean getSailState() {
        return dataManager.get(SAIL_STATE);
    }

    public void setSailState(boolean state) {
        if (state != getSailState()) {
            playSailSound(state);
            dataManager.set(SAIL_STATE, state);
        }
    }

    public void sendSailStateToServer(boolean state) {
        if (world.isRemote) {
            Main.SIMPLE_CHANNEL.sendToServer(new MessageSailState(state));
        }
    }

    public void playSailSound(boolean state) {
        if (state) {
            this.world.playSound(null, this.getPosX(), this.getPosY() + 4 , this.getPosZ(), SoundInit.SHIP_SAIL_0.get(), this.getSoundCategory(), 15.0F, 0.8F + 0.4F * this.rand.nextFloat());
        } else {
            this.world.playSound(null, this.getPosX(), this.getPosY() + 4, this.getPosZ(), SoundInit.SHIP_SAIL_1.get(), this.getSoundCategory(), 10.0F, 0.8F + 0.4F * this.rand.nextFloat());
        }
    }

    public void tick() {
        passengerwaittime--;
        if ((this.getControllingPassenger() == null ||!(this.getControllingPassenger() instanceof PlayerEntity) )&& getSailState()) {
            setSailState(false);
        }

        if (!(this.getControllingPassenger() == null) && (this.getControllingPassenger() instanceof PlayerEntity ) && this.forwardInputDown || this.getSailState()){
            if (this.getBoatStatus().equals(Status.IN_WATER))
                Watersplash();
        }

        this.previousStatus = this.status;
        this.status = this.getBoatStatus();
        if (this.status != TNBoatEntity.Status.UNDER_WATER && this.status != TNBoatEntity.Status.UNDER_FLOWING_WATER) {
            this.outOfControlTicks = 0.0F;
        } else {
            ++this.outOfControlTicks;
        }

        if (!this.world.isRemote && this.outOfControlTicks >= 60.0F) {
            this.removePassengers();
        }

        if (this.getTimeSinceHit() > 0) {
            this.setTimeSinceHit(this.getTimeSinceHit() - 1);
        }

        super.tick();
        this.tickLerp();
        if (this.canPassengerSteer()) {
            this.updateMotion();
            if (this.world.isRemote) {
                this.controlBoat();
            }
            this.move(MoverType.SELF, this.getMotion());
        } else {
            this.setMotion(Vector3d.ZERO);
        }

        if (getSailState() && this.getBoatStatus().equals(Status.IN_WATER) && this.getControllingPassenger() instanceof PlayerEntity && SmallShipsConfig.PlaySwimmSound.get() ) {
            this.world.playSound(null, this.getPosX(), this.getPosY(), this.getPosZ(), SoundEvents.ENTITY_GENERIC_SWIM, this.getSoundCategory(), 0.05F, 0.8F + 0.4F * this.rand.nextFloat());

        }

        this.doBlockCollisions();
        List<Entity> list = this.world.getEntitiesInAABBexcluding(this, this.getBoundingBox().grow((double) 0.2F, (double) -0.01F, (double) 0.2F), EntityPredicates.pushableBy(this));
        if (!list.isEmpty()) {
            boolean flag = !this.world.isRemote && !(this.getControllingPassenger() instanceof PlayerEntity);

            for (int j = 0; j < list.size(); ++j) {
                Entity entity = list.get(j);
                if (!entity.isPassenger(this)) {
                    if (flag && this.getPassengers().size() < 5 && !entity.isPassenger() && entity.getWidth() < this.getWidth() && entity instanceof LivingEntity && !(entity instanceof WaterMobEntity) && !(entity instanceof PlayerEntity)) {
                        if (passengerwaittime < 0) {
                            entity.startRiding(this);
                        }
                    } else {
                        this.applyEntityCollision(entity);
                    }
                }
            }
        }
    }

    @Override
    public void onSprintPressed() {
        super.onSprintPressed();
        sendSailStateToServer(!getSailState());
    }

    @Override
    public void Watersplash(){
        super.Watersplash();
        Vector3d vector3d = this.getLook(0.0F);
        float f0 = MathHelper.cos(this.rotationYaw * ((float)Math.PI / 180F)) * 0.8F;
        float f1 = MathHelper.sin(this.rotationYaw * ((float)Math.PI / 180F)) * 0.8F;
        float f2 =  2.5F - this.rand.nextFloat() * 0.7F;
        for (int i = 0; i < 2; ++i) {
            this.world.addParticle(ParticleTypes.DOLPHIN, this.getPosX() - vector3d.x * (double) f2 + (double) f0, this.getPosY() - vector3d.y + 0.5D, this.getPosZ() - vector3d.z * (double) f2 + (double) f1, 0.0D, 0.0D, 0.0D);
            this.world.addParticle(ParticleTypes.DOLPHIN, this.getPosX() - vector3d.x * (double) f2 - (double) f0, this.getPosY() - vector3d.y + 0.5D, this.getPosZ() - vector3d.z * (double) f2 - (double) f1, 0.0D, 0.0D, 0.0D);
            this.world.addParticle(ParticleTypes.DOLPHIN, this.getPosX() - vector3d.x * (double) f2 + (double) f0, this.getPosY() - vector3d.y + 0.5D, this.getPosZ() - vector3d.z * (double) f2 + (double) f1 * 1.1, 0.0D, 0.0D, 0.0D);
            this.world.addParticle(ParticleTypes.DOLPHIN, this.getPosX() - vector3d.x * (double) f2 - (double) f0, this.getPosY() - vector3d.y + 0.5D, this.getPosZ() - vector3d.z * (double) f2 - (double) f1 * 1.1, 0.0D, 0.0D, 0.0D);

            this.world.addParticle(ParticleTypes.SPLASH, this.getPosX() - vector3d.x * (double) f2 + (double) f0, this.getPosY() - vector3d.y + 0.8D, this.getPosZ() - vector3d.z * (double) f2 + (double) f1, 0.0D, 0.0D, 0.0D);
            this.world.addParticle(ParticleTypes.SPLASH, this.getPosX() - vector3d.x * (double) f2 - (double) f0, this.getPosY() - vector3d.y + 0.8D, this.getPosZ() - vector3d.z * (double) f2 - (double) f1, 0.0D, 0.0D, 0.0D);
            this.world.addParticle(ParticleTypes.SPLASH, this.getPosX() - vector3d.x * (double) f2 + (double) f0, this.getPosY() - vector3d.y + 0.8D, this.getPosZ() - vector3d.z * (double) f2 + (double) f1 * 1.1, 0.0D, 0.0D, 0.0D);
            this.world.addParticle(ParticleTypes.SPLASH, this.getPosX() - vector3d.x * (double) f2 - (double) f0, this.getPosY() - vector3d.y + 0.8D, this.getPosZ() - vector3d.z * (double) f2 - (double) f1 * 1.1, 0.0D, 0.0D, 0.0D);

        }
    }

    public void tickLerp() {
    }

    public Status getBoatStatus() {
        Status boatentity$status = getUnderwaterStatus();
        if (boatentity$status != null) {
            this.waterLevel = (getBoundingBox()).maxY;
            return boatentity$status;
        }
        if (checkInWater())
            return Status.IN_WATER;
        float f = getBoatGlide();
        if (f > 0.0F) {
            this.boatGlide = 0F;
            return Status.ON_LAND;
        }
        return Status.IN_AIR;
    }

    public void updateMotion() {
        double d0 = -0.04D; // down/up moment
        double d1 = hasNoGravity() ? 0.0D : d0;
        double d2 = 0.0D;  //
        double CogTurnFactor = SmallShipsConfig.CogTurnFactor.get();
        this.momentum = 1.0F;

        if (this.getPassengers().size() == 2) this.passengerfaktor = 0.05F;
        if (this.getPassengers().size() == 4) this.passengerfaktor = 0.1F;
        if (this.getPassengers().size() == 6) this.passengerfaktor = 0.2F;
        if (this.getPassengers().size() == 8) this.passengerfaktor = 0.3F;
        if (this.getPassengers().size() > 8) this.passengerfaktor = 0.4F;

        if (this.previousStatus == Status.IN_AIR && this.status != Status.IN_AIR && this.status != Status.ON_LAND) {
            this.waterLevel = (getBoundingBox()).minY + getHeight();
            setPosition(getPosX(), (getWaterLevelAbove() - getHeight()) + 0.101D, getPosZ());
            setMotion(getMotion().mul(10.0D, 0.0D, 10.0D));
            this.status = Status.IN_WATER;
        } else {
            if (this.status == Status.IN_WATER) {
                d2 = (this.waterLevel - (getBoundingBox()).minY + 0.1D) / getHeight();
                this.momentum = 0.9F;
            } else if (this.status == Status.UNDER_FLOWING_WATER) {
                d1 = -7.0E-4D;
                this.momentum = 0.9F;
            } else if (this.status == Status.UNDER_WATER) {
                d2 = 0.009999999776482582D;
                this.momentum = 0.45F;
            } else if (this.status == Status.IN_AIR) {
                this.momentum = 0.9F;
            } else if (this.status == Status.ON_LAND) {
                this.momentum = this.boatGlide * 0.001F;
                if (getControllingPassenger() instanceof PlayerEntity)
                    this.boatGlide /= 1.0F;
            }
            Vector3d vec3d = getMotion();
            setMotion(vec3d.x * (this.momentum - this.passengerfaktor), vec3d.y + d1, vec3d.z * (this.momentum - this.passengerfaktor));
            this.deltaRotation *= (this.momentum - this.passengerfaktor) * CogTurnFactor;
            if (d2 > 0.0D) {
                Vector3d vec3d1 = getMotion();
                setMotion(vec3d1.x, (vec3d1.y + d2 * 0.06D) * 0.75D, vec3d1.z);
            }
        }
    }

    protected void controlBoat() {
        double CogSpeedFactor = SmallShipsConfig.CogSpeedFactor.get();
        if (this.isBeingRidden()) {
            float f = 0.0F;
            if (this.leftInputDown) {
                --this.deltaRotation;
            }
            if (this.rightInputDown) {
                ++this.deltaRotation;
            }
            if (this.rightInputDown != this.leftInputDown && !this.forwardInputDown && !this.backInputDown) {
                f += 0.005F;
            }
            this.rotationYaw += this.deltaRotation;

            if (getSailState()) {
                f += (0.04F * CogSpeedFactor);
            }
            if (this.backInputDown) {
                f -= (0.005F * CogSpeedFactor);
            }
            if (this.forwardInputDown) {
                f += (0.005F * CogSpeedFactor);
            }
            this.setMotion(this.getMotion().add((double) (MathHelper.sin(-this.rotationYaw * ((float) Math.PI / 180F)) * f), 0.0D, (double) (MathHelper.cos(this.rotationYaw * ((float) Math.PI / 180F)) * f)));
        }
    }

    public IPacket<?> createSpawnPacket() {
        return NetworkHooks.getEntitySpawningPacket((Entity) this);
    }

    @Nullable
    private Status getUnderwaterStatus() {
        AxisAlignedBB axisalignedbb = this.getBoundingBox();
        double d0 = axisalignedbb.maxY + 0.001D;
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.ceil(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.maxY);
        int l = MathHelper.ceil(d0);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.ceil(axisalignedbb.maxZ);
        boolean flag = false;
        BlockPos.Mutable blockpos$mutable = new BlockPos.Mutable();

        for (int k1 = i; k1 < j; ++k1) {
            for (int l1 = k; l1 < l; ++l1) {
                for (int i2 = i1; i2 < j1; ++i2) {
                    blockpos$mutable.setPos(k1, l1, i2);
                    FluidState fluidstate = this.world.getFluidState(blockpos$mutable);
                    if (fluidstate.isTagged(FluidTags.WATER) && d0 < (double) ((float) blockpos$mutable.getY() + fluidstate.getActualHeight(this.world, blockpos$mutable))) {
                        if (!fluidstate.isSource()) {
                            return Status.UNDER_FLOWING_WATER;
                        }

                        flag = true;
                    }
                }
            }
        }

        return flag ? TNBoatEntity.Status.UNDER_WATER : null;
    }

    private boolean checkInWater() {
        AxisAlignedBB axisalignedbb = this.getBoundingBox();
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.ceil(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.minY);
        int l = MathHelper.ceil(axisalignedbb.minY + 0.001D);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.ceil(axisalignedbb.maxZ);
        boolean flag = false;
        this.waterLevel = Double.MIN_VALUE;
        BlockPos.Mutable blockpos$mutable = new BlockPos.Mutable();

        for (int k1 = i; k1 < j; ++k1) {
            for (int l1 = k; l1 < l; ++l1) {
                for (int i2 = i1; i2 < j1; ++i2) {
                    blockpos$mutable.setPos(k1, l1, i2);
                    FluidState fluidstate = this.world.getFluidState(blockpos$mutable);
                    if (fluidstate.isTagged(FluidTags.WATER)) {
                        float f = (float) l1 + fluidstate.getActualHeight(this.world, blockpos$mutable);
                        this.waterLevel = Math.max((double) f, this.waterLevel);
                        flag |= axisalignedbb.minY < (double) f;
                    }
                }
            }
        }

        return flag;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    /**
     * Applies a velocity to the entities, to push them away from eachother.
     */
    @Override
    public void applyEntityCollision(Entity entityIn) {
        super.applyEntityCollision(entityIn);
    }

    public boolean replaceItemInInventory(int inventorySlot, ItemStack itemStackIn) {
        if (inventorySlot >= 0 && inventorySlot < this.inventory.getSlots()) {
            this.inventory.setStackInSlot(inventorySlot, itemStackIn);
            return true;
        }
        return false;
    }

    public void onDestroyedAndDoDrops(DamageSource source) {
        for (int i = 0; i < this.inventory.getSlots(); i++)
            InventoryHelper.spawnItemStack(this.world, getPosX(), getPosY(), getPosZ(), this.inventory.getStackInSlot(i));
    }

    protected void readAdditional(CompoundNBT compound) {
        super.readAdditional(compound);
        this.inventory.deserializeNBT(compound.getCompound("Items"));
    }

    protected void writeAdditional(CompoundNBT compound) {
        super.writeAdditional(compound);
        compound.put("Items", (INBT) this.inventory.serializeNBT());

    }

    public void remove(boolean keepData) {
        super.remove(keepData);
        if (!keepData && this.itemHandler != null) {
            this.itemHandler.invalidate();
            this.itemHandler = null;
        }
    }

    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction facing) {
        if (isAlive() && capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && this.itemHandler != null)
            return this.itemHandler.cast();
        return super.getCapability(capability, facing);
    }

    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (isInvulnerableTo(source))
            return false;
        if (!this.world.isRemote && isAlive()) {
            if (source == DamageSource.CACTUS)
                return false;
            if (source instanceof net.minecraft.util.IndirectEntityDamageSource && source.getTrueSource() != null && isPassenger(source.getTrueSource()))
                return false;
            setForwardDirection(-getForwardDirection());
            setTimeSinceHit(3);
            setDamageTaken(getDamageTaken() + amount * 10.0F);
            boolean flag = (source.getTrueSource() instanceof PlayerEntity && ((PlayerEntity) source.getTrueSource()).abilities.isCreativeMode);
            if (flag || getDamageTaken() > 300.0F) {
                onDestroyed(source, flag);
                remove();
            }
            return true;
        }
        return false;
    }

    public void onDestroyed(DamageSource source, boolean byCreativePlayer) {
        if (this.world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {
            if (!byCreativePlayer)
                this.entityDropItem(this.getItemBoat());
            onDestroyedAndDoDrops(source);
        }
    }

    public float WaveMotion() {
        float wavestr = 2.0F;
        if (world.isRaining()) return 1.5F * wavestr;
        else return wavestr;
    }

    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
    }

    public Vector3d func_230268_c_(final LivingEntity rider) {
        return super.func_230268_c_(rider);
    }

    public PlayerEntity getDriver() {
        return super.getDriver();

    }

    @OnlyIn(Dist.CLIENT)
    public void updateInputs(boolean leftInputDown, boolean rightInputDown, boolean forwardInputDown, boolean backInputDown) {
        this.leftInputDown = leftInputDown;
        this.rightInputDown = rightInputDown;
        this.forwardInputDown = forwardInputDown;
        this.backInputDown = backInputDown;
    }
}