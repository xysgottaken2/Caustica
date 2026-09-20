package dev.xys.vulkanrt.build;

import org.lwjgl.util.shaderc.Shaderc;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Map;

/** Deterministic offline ShaderC compilation. Native bindings are build dependencies, not JNI code. */
public final class CompileRtShaders {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("source-directory output-directory");
        Path source = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0 || options == 0) {
            if (options != 0) Shaderc.shaderc_compile_options_release(options);
            if (compiler != 0) Shaderc.shaderc_compiler_release(compiler);
            throw new IllegalStateException("ShaderC initialization failed");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_4);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            Shaderc.shaderc_compile_options_set_warnings_as_errors(options);
            Map<String, Integer> stages = Map.of("rgen", Shaderc.shaderc_raygen_shader,
                    "rmiss", Shaderc.shaderc_miss_shader, "rchit", Shaderc.shaderc_closesthit_shader, "comp", Shaderc.shaderc_compute_shader);
            for (String name : new String[]{"primary.rgen","primary.rmiss","primary.rchit","chunks.rgen","chunks.rmiss","chunks.rchit","overlay.comp"}) {
                String stage=name.substring(name.lastIndexOf('.')+1);
                long result = Shaderc.shaderc_compile_into_spv(compiler, Files.readString(source.resolve(name)), stages.get(stage), name, "main", options);
                if (result == 0) throw new IllegalStateException("ShaderC returned no result for " + name);
                try {
                    if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success)
                        throw new IllegalStateException(name + ": " + Shaderc.shaderc_result_get_error_message(result));
                    ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                    if (bytes == null) throw new IllegalStateException("No SPIR-V for " + name);
                    byte[] data = new byte[bytes.remaining()]; bytes.get(data);
                    Files.write(output.resolve(name + ".spv"), data);
                } finally { Shaderc.shaderc_result_release(result); }
            }
        } finally { Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler); }
    }
}
