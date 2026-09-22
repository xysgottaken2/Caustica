package dev.comfyfluffy.caustica.rt.terrain;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Custom fluid mesher used in place of vanilla {@link FluidRenderer} for every fluid in the RT terrain
 * pipeline. Adapted from {@code FluidRenderer.tesselate} (26.2), keeping its corner-height averaging
 * and flow-direction UVs, but fixing three habits that are invisible in a backface-culled rasterizer
 * and actively wrong once every emitted triangle is a real path-traced dielectric interface:
 *
 * <ul>
 *   <li><b>No back faces.</b> Vanilla doubles the top face when {@code shouldRenderBackwardUpFace} and
 *       doubles every non-overlay side face unconditionally, relying on backface culling to hide one
 *       copy. We trace both sides of every triangle, so this doubled the water prim count and any-hit
 *       shadow work, and (with a duplicated, oppositely-wound copy at the same position) made the
 *       geometric normal ambiguous for medium tracking.
 *   <li><b>Real-shape neighbour occlusion.</b> Vanilla culls a fluid face using the neighbour's render
 *       <em>occlusion</em> shape ({@code getFaceOcclusionShape}), which is deliberately empty for any
 *       block with {@code noOcclusion()} — glass, stained glass, tinted glass, ice, slime, honey. Water
 *       therefore emits full faces against those blocks, and each one is a spurious water→air interface
 *       for us (an underwater glass structure reads as an air-filled dome: Fresnel + total internal
 *       reflection at grazing angles, absorption ending where it shouldn't). We cull instead using the
 *       neighbour's real (collision/visual) {@link BlockState#getShape}, which is still a full cube for
 *       all of those blocks — the boundary is then represented solely by the glass/ice/etc. block's own
 *       quad, never culled against a non-solid-shaped fluid.
 *   <li><b>Full height under any occluding ceiling.</b> Vanilla's {@code getHeight} only reports 1.0
 *       when the block above is the same fluid; a source block covered by an ordinary solid or a glass
 *       ceiling still reports {@code getOwnHeight()} (8/9), and its own occlusion special-case for the
 *       UP direction requires height==1.0 <em>and</em> a full occluder before it will cull that top
 *       face — so a covered fluid cell always gets a phantom top face (and, on the sides, a thin
 *       uncovered sliver up to y=1) that is merely invisible in vanilla (backface-culled from below, and
 *       no camera can be above an opaque ceiling to see the front). Here, a cell occluded from above
 *       (by real shape, so glass ceilings count too) reports height 1.0, matching the same-fluid-above
 *       rule and removing both the phantom face and the sliver.
 *   <li><b>Exact coordinates.</b> No 0.001 raster anti-z-fight insets/lifts — ray tracing doesn't
 *       z-fight, and the occlusion fixes above already remove every case that needed them.
 * </ul>
 *
 * Per-vertex cardinal lighting and the leaves/glass overlay sprite are also dropped: {@link RtTerrain}'s
 * {@code FluidCapture} never reads the light/overlay/normal fields {@link FluidRenderer.Output}'s
 * {@code VertexConsumer} is handed (it computes its own geometric normal and derives the water tint by
 * averaging the raw per-vertex colour), so there is nothing downstream to feed them for.
 */
final class RtFluidMesher {
    private RtFluidMesher() {
    }

    private static boolean isNeighborSameFluid(FluidState fluidState, FluidState neighborFluidState) {
        return neighborFluidState.getType().isSame(fluidState.getType());
    }

    /** Unchanged from vanilla: does THIS cell's own block state (a waterlogged partial block sharing
     *  the cell with the fluid) occlude the fluid's own face in {@code direction}? Only matters for
     *  waterlogged stairs/slabs/etc., which are solid-ish and already have a real occlusion shape. */
    private static boolean isFaceOccludedBySelf(BlockState state, Direction direction) {
        VoxelShape occluder = state.getFaceOcclusionShape(direction);
        if (occluder == Shapes.empty()) {
            return false;
        } else if (occluder == Shapes.block()) {
            return true;
        } else {
            VoxelShape shape = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
            return Shapes.blockOccludes(shape, occluder, direction.getOpposite());
        }
    }

    private static boolean shouldRenderFace(FluidState fluidState, BlockState blockState, Direction direction,
                                            FluidState neighborFluidState) {
        return !isNeighborSameFluid(fluidState, neighborFluidState) && !isFaceOccludedBySelf(blockState, direction);
    }

    /** Does {@code neighborState}'s REAL (collision/visual) shape occlude the fluid face pointing at it
     *  in {@code towardNeighbor}? This is the fix over vanilla's occlusion-shape-based check: glass,
     *  ice, slime, honey and tinted glass all report an empty render occlusion shape ({@code
     *  noOcclusion()}) but keep a full-cube real shape (they're still solid to walk into), so this
     *  correctly culls the fluid face against them while vanilla's check does not. */
    private static boolean neighborOccludesFace(BlockAndTintGetter level, BlockPos neighborPos, BlockState neighborState,
                                                Direction towardNeighbor, float faceHeight) {
        VoxelShape neighborShape = neighborState.getShape(level, neighborPos);
        if (neighborShape.isEmpty()) {
            return false;
        }
        VoxelShape faceShape = Shapes.box(0.0, 0.0, 0.0, 1.0, faceHeight, 1.0);
        return Shapes.blockOccludes(faceShape, neighborShape, towardNeighbor);
    }

    static void tesselate(BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output,
                          FluidStateModelSet fluidModels, BlockState blockState, FluidState fluidState) {
        BlockPos posDown = pos.below();
        BlockState blockStateDown = level.getBlockState(posDown);
        FluidState fluidStateDown = blockStateDown.getFluidState();
        BlockPos posUp = pos.above();
        BlockState blockStateUp = level.getBlockState(posUp);
        FluidState fluidStateUp = blockStateUp.getFluidState();
        BlockState blockStateNorth = level.getBlockState(pos.north());
        FluidState fluidStateNorth = blockStateNorth.getFluidState();
        BlockState blockStateSouth = level.getBlockState(pos.south());
        FluidState fluidStateSouth = blockStateSouth.getFluidState();
        BlockState blockStateWest = level.getBlockState(pos.west());
        FluidState fluidStateWest = blockStateWest.getFluidState();
        BlockState blockStateEast = level.getBlockState(pos.east());
        FluidState fluidStateEast = blockStateEast.getFluidState();
        boolean renderUp = !isNeighborSameFluid(fluidState, fluidStateUp);
        boolean renderDown = shouldRenderFace(fluidState, blockState, Direction.DOWN, fluidStateDown);
        boolean renderNorth = shouldRenderFace(fluidState, blockState, Direction.NORTH, fluidStateNorth);
        boolean renderSouth = shouldRenderFace(fluidState, blockState, Direction.SOUTH, fluidStateSouth);
        boolean renderWest = shouldRenderFace(fluidState, blockState, Direction.WEST, fluidStateWest);
        boolean renderEast = shouldRenderFace(fluidState, blockState, Direction.EAST, fluidStateEast);
        if (!(renderUp || renderDown || renderEast || renderWest || renderNorth || renderSouth)) {
            return;
        }

        FluidModel model = fluidModels.get(fluidState);
        var builder = output.getBuilder(model.layer());
        BlockTintSource tintSource = model.tintSource();
        int tintColor = tintSource != null ? tintSource.colorInWorld(blockState, level, pos) : -1;
        Fluid type = fluidState.getType();
        float heightSelf = getHeight(level, type, pos, blockState, fluidState);
        float heightNorthEast;
        float heightNorthWest;
        float heightSouthEast;
        float heightSouthWest;
        if (heightSelf >= 1.0F) {
            heightNorthEast = 1.0F;
            heightNorthWest = 1.0F;
            heightSouthEast = 1.0F;
            heightSouthWest = 1.0F;
        } else {
            float heightNorth = getHeight(level, type, pos.north(), blockStateNorth, fluidStateNorth);
            float heightSouth = getHeight(level, type, pos.south(), blockStateSouth, fluidStateSouth);
            float heightEast = getHeight(level, type, pos.east(), blockStateEast, fluidStateEast);
            float heightWest = getHeight(level, type, pos.west(), blockStateWest, fluidStateWest);
            heightNorthEast = calculateAverageHeight(level, type, heightSelf, heightNorth, heightEast, pos.relative(Direction.NORTH).relative(Direction.EAST));
            heightNorthWest = calculateAverageHeight(level, type, heightSelf, heightNorth, heightWest, pos.relative(Direction.NORTH).relative(Direction.WEST));
            heightSouthEast = calculateAverageHeight(level, type, heightSelf, heightSouth, heightEast, pos.relative(Direction.SOUTH).relative(Direction.EAST));
            heightSouthWest = calculateAverageHeight(level, type, heightSelf, heightSouth, heightWest, pos.relative(Direction.SOUTH).relative(Direction.WEST));
        }

        float x = pos.getX() & 15;
        float y = pos.getY() & 15;
        float z = pos.getZ() & 15;
        if (renderUp && !neighborOccludesFace(level, posUp, blockStateUp, Direction.UP,
                Math.min(Math.min(heightNorthWest, heightSouthWest), Math.min(heightSouthEast, heightNorthEast)))) {
            Vec3 flow = fluidState.getFlow(level, pos);
            float u00;
            float u01;
            float u10;
            float u11;
            float v00;
            float v01;
            float v10;
            float v11;
            if (flow.x == 0.0 && flow.z == 0.0) {
                TextureAtlasSprite stillSprite = model.stillMaterial().sprite();
                u00 = stillSprite.getU0();
                v00 = stillSprite.getV0();
                u01 = u00;
                v01 = stillSprite.getV1();
                u10 = stillSprite.getU1();
                v10 = v01;
                u11 = u10;
                v11 = v00;
            } else {
                float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2);
                float s = Mth.sin(angle) * 0.25F;
                float c = Mth.cos(angle) * 0.25F;
                TextureAtlasSprite flowingSprite = model.flowingMaterial().sprite();
                u00 = flowingSprite.getU(0.5F + (-c - s));
                v00 = flowingSprite.getV(0.5F + (-c + s));
                u01 = flowingSprite.getU(0.5F + (-c + s));
                v01 = flowingSprite.getV(0.5F + (c + s));
                u10 = flowingSprite.getU(0.5F + (c + s));
                v10 = flowingSprite.getV(0.5F + (c - s));
                u11 = flowingSprite.getU(0.5F + (c - s));
                v11 = flowingSprite.getV(0.5F + (-c - s));
            }

            addFace(builder,
                    x + 0.0F, y + heightNorthWest, z + 0.0F, u00, v00,
                    x + 0.0F, y + heightSouthWest, z + 1.0F, u01, v01,
                    x + 1.0F, y + heightSouthEast, z + 1.0F, u10, v10,
                    x + 1.0F, y + heightNorthEast, z + 0.0F, u11, v11,
                    tintColor);
        }

        if (renderDown && !neighborOccludesFace(level, posDown, blockStateDown, Direction.DOWN, 1.0F)) {
            TextureAtlasSprite stillSprite = model.stillMaterial().sprite();
            float u0 = stillSprite.getU0();
            float u1 = stillSprite.getU1();
            float v0 = stillSprite.getV0();
            float v1 = stillSprite.getV1();
            addFace(builder,
                    x, y, z, u0, v0,
                    x + 1.0F, y, z, u1, v0,
                    x + 1.0F, y, z + 1.0F, u1, v1,
                    x, y, z + 1.0F, u0, v1,
                    tintColor);
        }

        for (Direction faceDir : Direction.Plane.HORIZONTAL) {
            float hh0;
            float hh1;
            float x0;
            float z0;
            float x1;
            float z1;
            boolean renderCondition;
            BlockPos neighborPos;
            BlockState neighborState;
            switch (faceDir) {
                case NORTH -> {
                    hh0 = heightNorthWest;
                    hh1 = heightNorthEast;
                    x0 = x;
                    x1 = x + 1.0F;
                    z0 = z;
                    z1 = z;
                    renderCondition = renderNorth;
                    neighborPos = pos.north();
                    neighborState = blockStateNorth;
                }
                case SOUTH -> {
                    hh0 = heightSouthEast;
                    hh1 = heightSouthWest;
                    x0 = x + 1.0F;
                    x1 = x;
                    z0 = z + 1.0F;
                    z1 = z + 1.0F;
                    renderCondition = renderSouth;
                    neighborPos = pos.south();
                    neighborState = blockStateSouth;
                }
                case WEST -> {
                    hh0 = heightSouthWest;
                    hh1 = heightNorthWest;
                    x0 = x;
                    x1 = x;
                    z0 = z + 1.0F;
                    z1 = z;
                    renderCondition = renderWest;
                    neighborPos = pos.west();
                    neighborState = blockStateWest;
                }
                case EAST -> {
                    hh0 = heightNorthEast;
                    hh1 = heightSouthEast;
                    x0 = x + 1.0F;
                    x1 = x + 1.0F;
                    z0 = z;
                    z1 = z + 1.0F;
                    renderCondition = renderEast;
                    neighborPos = pos.east();
                    neighborState = blockStateEast;
                }
                default -> throw new UnsupportedOperationException();
            }

            if (renderCondition && !neighborOccludesFace(level, neighborPos, neighborState, faceDir, Math.max(hh0, hh1))) {
                TextureAtlasSprite sprite = model.flowingMaterial().sprite();
                float u0 = sprite.getU(0.0F);
                float u1 = sprite.getU(0.5F);
                float v01 = sprite.getV((1.0F - hh0) * 0.5F);
                float v02 = sprite.getV((1.0F - hh1) * 0.5F);
                float v1 = sprite.getV(0.5F);
                addFace(builder,
                        x0, y + hh0, z0, u0, v01,
                        x1, y + hh1, z1, u1, v02,
                        x1, y, z1, u1, v1,
                        x0, y, z0, u0, v1,
                        tintColor);
            }
        }
    }

    private static void addFace(VertexConsumer builder,
                                float x0, float y0, float z0, float u0, float v0,
                                float x1, float y1, float z1, float u1, float v1,
                                float x2, float y2, float z2, float u2, float v2,
                                float x3, float y3, float z3, float u3, float v3,
                                int color) {
        vertex(builder, x0, y0, z0, color, u0, v0);
        vertex(builder, x1, y1, z1, color, u1, v1);
        vertex(builder, x2, y2, z2, color, u2, v2);
        vertex(builder, x3, y3, z3, color, u3, v3);
    }

    private static void vertex(VertexConsumer builder,
                               float x, float y, float z, int color, float u, float v) {
        // light/overlay/normal are unused by RtTerrain.FluidCapture (it computes its own geometric
        // normal and never reads light/overlay), so these are placeholders, not vanilla's real values.
        builder.addVertex(x, y, z, color, u, v, OverlayTexture.NO_OVERLAY, 0, 0.0F, 1.0F, 0.0F);
    }

    private static float calculateAverageHeight(BlockAndTintGetter level, Fluid type, float heightSelf,
                                                float height2, float height1, BlockPos cornerPos) {
        if (!(height1 >= 1.0F) && !(height2 >= 1.0F)) {
            float[] weightedHeight = new float[2];
            if (height1 > 0.0F || height2 > 0.0F) {
                float heightCorner = getHeight(level, type, cornerPos);
                if (heightCorner >= 1.0F) {
                    return 1.0F;
                }

                addWeightedHeight(weightedHeight, heightCorner);
            }

            addWeightedHeight(weightedHeight, heightSelf);
            addWeightedHeight(weightedHeight, height1);
            addWeightedHeight(weightedHeight, height2);
            return weightedHeight[0] / weightedHeight[1];
        } else {
            return 1.0F;
        }
    }

    private static void addWeightedHeight(float[] weightedHeight, float height) {
        if (height >= 0.8F) {
            weightedHeight[0] += height * 10.0F;
            weightedHeight[1] += 10.0F;
        } else if (height >= 0.0F) {
            weightedHeight[0] += height;
            weightedHeight[1]++;
        }
    }

    private static float getHeight(BlockAndTintGetter level, Fluid fluidType, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return getHeight(level, fluidType, pos, state, state.getFluidState());
    }

    /** Vanilla plus one addition: a cell covered by ANY real-shape-occluding neighbour above (not just
     *  the same fluid) reports full height, so the mesh has no phantom top face / sliver under a solid
     *  or glass-like ceiling — see the class doc's third bullet. */
    private static float getHeight(BlockAndTintGetter level, Fluid fluidType, BlockPos pos, BlockState state,
                                   FluidState fluidState) {
        if (!fluidType.isSame(fluidState.getType())) {
            return !state.isSolid() ? 0.0F : -1.0F;
        }
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (fluidType.isSame(aboveState.getFluidState().getType())) {
            return 1.0F;
        }
        if (neighborOccludesFace(level, abovePos, aboveState, Direction.UP, 1.0F)) {
            return 1.0F;
        }
        return fluidState.getOwnHeight();
    }
}
