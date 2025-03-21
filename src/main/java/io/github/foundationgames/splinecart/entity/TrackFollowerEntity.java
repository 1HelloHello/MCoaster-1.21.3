package io.github.foundationgames.splinecart.entity;

import io.github.foundationgames.splinecart.Splinecart;
import io.github.foundationgames.splinecart.block.TrackMarkerBlockEntity;
import io.github.foundationgames.splinecart.util.Pose;
import io.github.foundationgames.splinecart.util.SUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaternionf;
import org.joml.Vector3d;

public class TrackFollowerEntity extends Entity {

    public static final double FRICTION = 0.002; // in b/t that are removed for every b/t of speed every second

    public static final double GRAVITY_MPS2 = 9.81;
    public static final double GRAVITY = GRAVITY_MPS2 / (20*20); // in blocks/tick² (0.04)

    private static final boolean DESTROY_MINECART_END_OF_TRACK = true;

    public static final double METERS_PER_TICK_TO_KMH = 20 * 3.6;

    private @Nullable BlockPos startTie;
    private @Nullable BlockPos endTie;
    private double splinePieceProgress = 0; // t
    private double motionScale; // t-distance per block
    private double trackVelocity; // velocity in blocks / tick

    private final Vector3d serverPosition = new Vector3d();
    private final Vector3d serverVelocity = new Vector3d();
    private int positionInterpSteps;
    private int oriInterpSteps;

    private static final TrackedData<Quaternionf> ORIENTATION = DataTracker.registerData(TrackFollowerEntity.class, TrackedDataHandlerRegistry.QUATERNION_F);
    private final Matrix3d basis = new Matrix3d().identity();

    private final Quaternionf lastClientOrientation = new Quaternionf();
    private final Quaternionf clientOrientation = new Quaternionf();

    private boolean hadPassenger = false;
    private boolean hadPlayerPassenger = false;

    // for velocity display
    private Vector3d lastVelocity = new Vector3d();
    private Vector3d secondLastVelocity = new Vector3d();
    private Vector3d peakVelocity = new Vector3d();


    private boolean firstPositionUpdate = true;
    private boolean firstOriUpdate = true;

    private Vec3d clientMotion = Vec3d.ZERO;

    public TrackFollowerEntity(EntityType<?> type, World world) {
        super(type, world);
    }

    public TrackFollowerEntity(World world) {
        this(Splinecart.TRACK_FOLLOWER, world);
    }

    public static @Nullable TrackFollowerEntity create(World world, Vec3d startPos, BlockPos markerPos, Vec3d velocity) {
        var marker = TrackMarkerBlockEntity.of(world, markerPos);
        if(marker == null) {
            return null;
        }
        double trackVelocity, progress;
        BlockPos startMarkerPos, endMarkerPos;
        var markerDirection = new Vector3d(0, 0, 1).mul(marker.pose().basis()).normalize();
        var velocityDirection = new Vector3d(velocity.getX(), velocity.getY(), velocity.getZ()).normalize();

        if (markerDirection.dot(velocityDirection) >= 0) { // Heading in positive direction (in the direction of the arrows)
            trackVelocity = velocity.length();
            startMarkerPos = markerPos;
            endMarkerPos = marker.getNextTrackMarkerPos();
            progress = 0;
        }else {
            trackVelocity = -velocity.length();
            startMarkerPos = marker.getPrevTrackMarkerPos();
            endMarkerPos = markerPos;
            progress = 1;
        }

        var startMarker = TrackMarkerBlockEntity.of(world, startMarkerPos);
        if(startMarker == null) {
            return null;
        }
        var follower = new TrackFollowerEntity(world);
        follower.trackVelocity = trackVelocity;
        follower.splinePieceProgress = progress;
        follower.setStretch(startMarkerPos, endMarkerPos);
        follower.setPosition(startPos);
        follower.getDataTracker().set(ORIENTATION, startMarker.pose().basis().getNormalizedRotation(new Quaternionf()));
        return follower;
    }

    public static @Nullable TrackFollowerEntity create(World world, BlockPos markerPos, double trackVelocity) { // TODO refactor
        var marker = TrackMarkerBlockEntity.of(world, markerPos);
        if(marker == null) {
            return null;
        }
        BlockPos startMarkerPos, endMarkerPos;

        startMarkerPos = markerPos;
        endMarkerPos = marker.getNextTrackMarkerPos();

        var startMarker = TrackMarkerBlockEntity.of(world, startMarkerPos);
        if(startMarker == null) {
            return null;
        }
        var follower = new TrackFollowerEntity(world);
        follower.trackVelocity = trackVelocity;
        follower.splinePieceProgress = 0;
        follower.setStretch(startMarkerPos, endMarkerPos);
        follower.setPosition(SUtil.toCenteredVec3d(markerPos));
        follower.getDataTracker().set(ORIENTATION, startMarker.pose().basis().getNormalizedRotation(new Quaternionf()));
        return follower;
    }

    public void setStretch(@Nullable BlockPos start, @Nullable BlockPos end) {
        this.startTie = start;
        this.endTie = end;
    }

    // For more accurate client side position interpolation, we can conveniently use the
    // same cubic hermite spline formula rather than linear interpolation like vanilla,
    // since we have not only the position but also its derivative (velocity)
    protected void interpPos(int step) {
        double t = 1 / (double)step;

        var clientPos = new Vector3d(this.getX(), this.getY(), this.getZ());

        Vec3d velocity = this.getVelocity();
        Vector3d clientVel = new Vector3d(velocity.getX(), velocity.getY(), velocity.getZ());

        var newClientPos = new Vector3d();
        var newClientVel = new Vector3d();
        Pose.cubicHermiteSpline(t, 1, clientPos, clientVel, this.serverPosition, this.serverVelocity,
                newClientPos, newClientVel);
        this.setPosition(newClientPos.x(), newClientPos.y(), newClientPos.z());
        this.setVelocity(newClientVel.x(), newClientVel.y(), newClientVel.z());
    }

    @Override
    public void tick() {
        super.tick();
        if (super.getWorld().isClient()) {
            updateClient();
            updateRidingPassenger();
        } else {
            updateServer();
        }
    }

    protected void updateRidingPassenger() {
        Entity cart = super.getFirstPassenger();
        if(cart == null)
            return;
        Entity passenger = cart.getFirstPassenger();
        updatePlayerFacing(passenger);
        if(passenger == null)
            return;
        if(passenger instanceof PlayerEntity player) {
            updateSpeedInfo(player);
        }
    }

    protected void updatePlayerFacing(Entity passenger) {
        if(passenger == null && hadPlayerPassenger) {
            hadPlayerPassenger = false;
        }
        if(passenger != null && !hadPlayerPassenger) {
            if(passenger instanceof PlayerEntity player) {
                player.setYaw(90);
                player.setPitch(0);
            }
            hadPlayerPassenger = true;
        }
    }

    protected void updateSpeedInfo(PlayerEntity player) {
        Vector3d currentVelocity = serverVelocity;

        if(currentVelocity.length() > lastVelocity.length() && lastVelocity.length() < secondLastVelocity.length()) {
            peakVelocity = new Vector3d(lastVelocity);
        }else if(currentVelocity.length() < lastVelocity.length() && lastVelocity.length() > secondLastVelocity.length()) {
            peakVelocity = new Vector3d(lastVelocity);
        }

        Vector3d acceleration = new Vector3d(currentVelocity).sub(secondLastVelocity).mul((double) 1 / 2).add(0, GRAVITY, 0).mul(clientOrientation.get(new Matrix3d()));

        player.sendMessage(Text.of(
                doubleToString(currentVelocity.length()* METERS_PER_TICK_TO_KMH)
                        + " km/h peak: "
                        + doubleToString(peakVelocity.length() * METERS_PER_TICK_TO_KMH)
                        + " km/h --- acc: "
                        + signedDoubleToString(acceleration.y / GRAVITY)
                        + " Gv "
                        + signedDoubleToString(acceleration.x / GRAVITY)
                        + " Gh "
                        + signedDoubleToString(acceleration.z / GRAVITY)
                        + " Gl ")
                , true);
        secondLastVelocity = new Vector3d(lastVelocity);
        lastVelocity = new Vector3d(currentVelocity);
    }

    private static String signedDoubleToString(double value) {
        return (value < 0 ? "-" : "+") + doubleToString(Math.abs(value));
    }

    private static String doubleToString(double value) {
        String str = value + "00";
        if(str.contains("E")) {
            return "0.00";
        }
        int index = str.indexOf(".");
        return str.substring(0, index + 2 + 1);
    }

    public void getClientOrientation(Quaternionf q, float tickDelta) {
        this.lastClientOrientation.slerp(this.clientOrientation, tickDelta, q);
    }

    public Vec3d getClientMotion() {
        return this.clientMotion;
    }

    public Matrix3dc getServerBasis() {
        return this.basis;
    }

    public void destroy() {
        this.remove(RemovalReason.KILLED);
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;
    }

    private void flyOffTrack(Entity firstPassenger) {
        if(DESTROY_MINECART_END_OF_TRACK) {
            firstPassenger.remove(RemovalReason.KILLED);
            this.destroy();
            return;
        }
        firstPassenger.stopRiding();

        var newVel = new Vector3d(0, 0, this.trackVelocity).mul(this.basis);
        firstPassenger.setVelocity(newVel.x(), newVel.y(), newVel.z());
        this.destroy();
    }

    protected  void updateClient() {
        this.clientMotion = this.getPos().negate();
        if (this.positionInterpSteps > 0) {
            this.interpPos(this.positionInterpSteps);
            this.positionInterpSteps--;
        } else {
            this.refreshPosition();
            this.setVelocity(this.serverVelocity.x(), this.serverVelocity.y(), this.serverVelocity.z());
        }
        this.clientMotion = this.clientMotion.add(this.getPos());

        this.lastClientOrientation.set(this.clientOrientation);
        if (this.oriInterpSteps > 0) {
            float delta = 1 / (float) oriInterpSteps;
            this.clientOrientation.slerp(this.getDataTracker().get(ORIENTATION), delta);
            this.oriInterpSteps--;
        } else {
            this.clientOrientation.set(this.getDataTracker().get(ORIENTATION));
        }
    }

    protected void updateServer() {
        for (var passenger : this.getPassengerList()) {
            passenger.fallDistance = 0;
        }

        var passenger = this.getFirstPassenger();
        if(passenger == null) {
            if (this.hadPassenger) {
                this.destroy();
            }
            return;
        }

        if (!hadPassenger) {
            hadPassenger = true;
        } else {
            var world = this.getWorld();
            TrackMarkerBlockEntity startE = TrackMarkerBlockEntity.of(world, this.startTie);
            TrackMarkerBlockEntity endE = TrackMarkerBlockEntity.of(world, this.endTie);
            if (startE == null || endE == null) { // Backup; shouldn't normally execute
                this.destroy();
                return;
            }

            this.splinePieceProgress += this.trackVelocity * this.motionScale;
            if (this.splinePieceProgress > 1) {
                this.splinePieceProgress -= 1;
                endE.setLastVelocity(super.getVelocity().length());
                endE.markDirty();
                endE.triggers.execute(getWorld());

                var nextE = endE.getNextMarker();
                if (nextE == null) {
                    this.flyOffTrack(passenger);
                    return;
                }
                this.setStretch(this.endTie, nextE.getPos());
                startE = endE;
                endE = nextE;
            } else if (this.splinePieceProgress < 0) {
                this.splinePieceProgress += 1;
                startE.setLastVelocity(-super.getVelocity().length());
                startE.markDirty();
                startE.triggers.execute(getWorld());

                var prevE = startE.getPrevMarker();
                if (prevE == null) {
                    this.flyOffTrack(passenger);
                    return;
                }
                this.setStretch(prevE.getPos(), this.startTie);
                endE = startE;
                startE = prevE;
            }

            var pos = new Vector3d();
            var grad = new Vector3d(); // Change in position per change in spline progress
            startE.pose().interpolate(endE.pose(), this.splinePieceProgress, pos, this.basis, grad);

            this.setPosition(pos.x(), pos.y(), pos.z());
            this.getDataTracker().set(ORIENTATION, this.basis.getNormalizedRotation(new Quaternionf()));

            double gradLen = grad.length();
            if (gradLen != 0) {
                this.motionScale = 1 / grad.length();
            }

            var ngrad = new Vector3d(grad).normalize();
            var gravity = -ngrad.y() * GRAVITY;

            double dt = this.trackVelocity * this.motionScale; // Change in spline progress per tick
            grad.mul(dt); // Change in position per tick (velocity)
            this.setVelocity(grad.x(), grad.y(), grad.z());

            var passengerVel = passenger.getVelocity();
            var push = new Vector3d(passengerVel.getX(), 0.0, passengerVel.getZ());
            if (push.lengthSquared() > 0.0001) {
                var forward = new Vector3d(0, 0, 1).mul(this.basis);

                double linearPush = forward.dot(push) * 2.0;
                this.trackVelocity += linearPush;
                passenger.setVelocity(Vec3d.ZERO);
            }

            var gradeVec = new Vector3d(0, 1, 0).mul(this.basis);
            gradeVec.mul(1, 0, 1);
            double power = startE.computePower();
            double strength = startE.computeStrength();

            this.trackVelocity += gravity;
            this.trackVelocity = startE.nextType.motion.calculate(this.trackVelocity, gradeVec.length(), power, strength);
        }
    }

    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps) {
        if (this.firstPositionUpdate) {
            this.firstPositionUpdate = false;
            super.updateTrackedPositionAndAngles(x, y, z, yaw, pitch, interpolationSteps);
        }

        this.serverPosition.set(x, y, z);
        this.positionInterpSteps = interpolationSteps + 2;
        this.setAngles(yaw, pitch);
    }

    // This method should be called updateTrackedVelocity, its usage is very similar to the above method
    @Override
    public void setVelocityClient(double x, double y, double z) {
        this.serverVelocity.set(x, y, z);
    }

    @Override
    protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
        positionUpdater.accept(passenger, this.getX(), this.getY(), this.getZ());
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(ORIENTATION, new Quaternionf().identity());
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);

        if (data.equals(ORIENTATION)) {
            if (this.firstOriUpdate) {
                this.firstOriUpdate = false;
                this.clientOrientation.set(getDataTracker().get(ORIENTATION));
                this.lastClientOrientation.set(this.clientOrientation);
            }
            this.oriInterpSteps = this.getType().getTrackTickInterval() + 2;
        }
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        this.startTie = SUtil.getBlockPos(nbt, "startMarkerPos");
        this.endTie = SUtil.getBlockPos(nbt, "endMarkerPos");
        this.trackVelocity = nbt.getDouble("track_velocity");
        this.motionScale = nbt.getDouble("motion_scale");
        this.splinePieceProgress = nbt.getDouble("spline_piece_progress");
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        SUtil.putBlockPos(nbt, this.startTie, "startMarkerPos");
        SUtil.putBlockPos(nbt, this.endTie, "endMarkerPos");
        nbt.putDouble("track_velocity", this.trackVelocity);
        nbt.putDouble("motion_scale", this.motionScale);
        nbt.putDouble("spline_piece_progress", this.splinePieceProgress);
    }
}
