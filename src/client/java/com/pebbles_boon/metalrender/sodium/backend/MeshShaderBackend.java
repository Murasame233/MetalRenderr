package com.pebbles_boon.metalrender.sodium.backend;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.MeshShaderNative;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MeshShaderBackend {
  private long[] pipelines = new long[3];
  private long fallbackPipelineHandle;
  private boolean active;
  private boolean meshShadersAvailable;
  private boolean gpuDrivenEnabled;
  private ByteBuffer meshletUploadBuffer;
  private int uploadCap;
  private int meshlets;
  private int visible;
  private int threadgroups;
  private long lastStatsLogMs;
  private static final long STATS_LOG_INTERVAL_MS = 5000;

  public void initialize() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    meshShadersAvailable = MetalHardwareChecker.supportsMeshShaders();
    long library = renderer.getBackend().getShaderLibraryHandle();
    if (meshShadersAvailable && library != 0) {
      long[] handles = MeshShaderNative.createTerrainMeshPipelines(library);
      if (handles != null && handles.length >= 3) {
        pipelines[0] = handles[0];
        pipelines[1] = handles[1];
        pipelines[2] = handles[2];
        if (handles[0] != 0) {
          MetalLogger.info("Mesh shader terrain pipelines created: opaque=0x%X, cutout=0x%X, emissive=0x%X",
              handles[0], handles[1], handles[2]);
        }
      }
      if (pipelines[0] == 0) {
        long device = renderer.getBackend().getDeviceHandle();
        fallbackPipelineHandle = MeshShaderNative.createMeshPipeline(
            device, library, "object_terrain", "mesh_terrain",
            "fragment_terrain_mesh_opaque");
      }
    }
    uploadCap = 4096;
    meshletUploadBuffer = ByteBuffer.allocateDirect(uploadCap * 32)
        .order(ByteOrder.nativeOrder());
    active = true;
    gpuDrivenEnabled = meshShadersAvailable && (pipelines[0] != 0 || fallbackPipelineHandle != 0);
    MetalLogger.info("Mesh shader backend initialized (mesh shaders: %s, GPU-driven: %s, pipelines: %d)",
        meshShadersAvailable ? "supported" : "unsupported",
        gpuDrivenEnabled ? "enabled" : "disabled",
        MeshShaderNative.getActivePipelineCount());
  }

  public void prepareMeshlets(int[] meshletVertexOffsets, int[] meshletIndexOffsets,
      int[] meshletVertexCounts, int[] meshletTriangleCounts,
      int[] meshletLodLevels, int[] meshletChunkIndices, int count) {
    if (!active || !gpuDrivenEnabled || count <= 0)
      return;
    if (count > uploadCap) {
      uploadCap = count + (count >> 2);
      meshletUploadBuffer = ByteBuffer.allocateDirect(uploadCap * 32)
          .order(ByteOrder.nativeOrder());
    }
    meshletUploadBuffer.clear();
    for (int i = 0; i < count; i++) {
      meshletUploadBuffer.putInt(meshletVertexOffsets[i]);
      meshletUploadBuffer.putInt(meshletIndexOffsets[i]);
      meshletUploadBuffer.putInt(meshletVertexCounts[i]);
      meshletUploadBuffer.putInt(meshletTriangleCounts[i]);
      meshletUploadBuffer.putInt(meshletLodLevels[i]);
      meshletUploadBuffer.putInt(meshletChunkIndices[i]);
      meshletUploadBuffer.putInt(0);
      meshletUploadBuffer.putInt(0);
    }
    meshletUploadBuffer.flip();
    MeshShaderNative.uploadMeshletBuffer(0, meshletUploadBuffer, count);
    meshlets = count;
  }

  public void drawChunkMesh(long ctx, long argBuf,
      int objectThreadgroups, int meshThreadsPerGroup) {
    if (!active || ctx == 0)
      return;
    long pipeline = getPipeline(0);
    if (pipeline != 0) {
      MeshShaderNative.drawMeshThreadgroups(
          ctx, pipeline, objectThreadgroups,
          meshThreadsPerGroup, argBuf);
      threadgroups = objectThreadgroups;
    }
    logStatsIfNeeded();
  }

  public void drawChunkMeshPass(long ctx, int passIndex,
      long argBuf, int threadgroups) {
    if (!active || ctx == 0 || passIndex < 0 || passIndex > 2)
      return;
    long pipeline = getPipeline(passIndex);
    if (pipeline != 0) {
      MeshShaderNative.drawMeshThreadgroups(
          ctx, pipeline, threadgroups, 256, argBuf);
    }
  }

  public void dispatchTerrainFromCullResults(long handle, long argumentBuffer) {
    if (!active)
      return;
    int visibleCount = NativeBridge.nGetGPUVisibleCount(handle);
    visible = visibleCount;
    if (visibleCount > 0) {
      MeshShaderNative.dispatchTerrain(handle, visibleCount, argumentBuffer);
    }
  }

  private long getPipeline(int passIndex) {
    if (passIndex >= 0 && passIndex < 3 && pipelines[passIndex] != 0) {
      return pipelines[passIndex];
    }
    return fallbackPipelineHandle;
  }

  private void logStatsIfNeeded() {
    long now = System.currentTimeMillis();
    if (now - lastStatsLogMs > STATS_LOG_INTERVAL_MS) {
      lastStatsLogMs = now;
      MetalLogger.info("MeshShader: visible=%d, threadgroups=%d, meshlets=%d, pipelines=%d",
          visible, threadgroups, meshlets,
          MeshShaderNative.getActivePipelineCount());
    }
  }

  public void shutdown() {
    for (int i = 0; i < 3; i++) {
      if (pipelines[i] != 0) {
        MeshShaderNative.destroyMeshPipeline(pipelines[i]);
        pipelines[i] = 0;
      }
    }
    if (fallbackPipelineHandle != 0) {
      MeshShaderNative.destroyMeshPipeline(fallbackPipelineHandle);
      fallbackPipelineHandle = 0;
    }
    meshletUploadBuffer = null;
    active = false;
    gpuDrivenEnabled = false;
  }

  public boolean isActive() {
    return active;
  }

  public boolean areMeshShadersAvailable() {
    return meshShadersAvailable;
  }

  public boolean isGPUDrivenEnabled() {
    return gpuDrivenEnabled;
  }

  public int getVisible() {
    return visible;
  }

  public int getMeshlets() {
    return meshlets;
  }
}
