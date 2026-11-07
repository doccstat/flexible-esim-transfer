"""Explicit Clang module compilation, including hosts whose SDK Ninja predates C++ modules."""
import os
from pathlib import Path
import re
import subprocess

TARGETS = {'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'armv7a-linux-androideabi',
           'x86_64': 'x86_64-linux-android', 'x86': 'i686-linux-android'}


def module_graph(sources):
    graph = {}
    for path in sources:
        text = path.read_text(encoding='utf-8')
        match = re.search(r'^export module ([\w:]+);', text, re.M)
        if not match:
            continue
        name = match[1]
        if name in graph:
            raise ValueError('Duplicate C++ module')
        imports = re.findall(r'^(?:export )?import ([\w:]+);', text, re.M)
        graph[name] = (path, {name.split(':')[0] + item if item.startswith(':') else item for item in imports})
    for _, dependencies in graph.values():
        if not dependencies.issubset(graph):
            raise ValueError('Missing C++ module')
    result = []
    while graph:
        ready = sorted(name for name, (_, deps) in graph.items() if not deps.intersection(graph))
        if not ready:
            raise ValueError('Cyclic C++ module graph')
        for name in ready:
            path, _ = graph.pop(name)
            result.append((name, path))
    return result


def native_build(args, source, out, abi):
    def run(command): subprocess.run(list(map(str, command)), check=True)
    out.mkdir()
    host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    toolchain = args.ndk.resolve() / 'toolchains/llvm/prebuilt' / host
    compiler = toolchain / 'bin' / ('clang++.exe' if os.name == 'nt' else 'clang++')
    lsplant = source / 'vendor/lsplant/lsplant/src/main/jni'
    dex = lsplant / 'external/dex_builder'
    includes = [source / 'include', source / 'native', source / 'vendor/dobby/include', lsplant,
                lsplant / 'include', dex, dex / 'include', dex / 'external/parallel_hashmap']
    flags = ['--target=' + TARGETS[abi] + '33', '--sysroot=' + str(toolchain / 'sysroot'),
             '-std=c++23', '-O2', '-DNDEBUG', '-fPIC', '-fvisibility=hidden', '-ffunction-sections', '-fdata-sections']
    for include in includes: flags.extend(['-I', str(include)])
    modules = module_graph([*lsplant.rglob('*.ixx'), *lsplant.rglob('*.cxx')])
    mappings = []; objects = []
    for name, path in modules:
        stem = name.replace(':', '-')
        pcm = out / (stem + '.pcm'); obj = out / (stem + '.o')
        run([compiler, *flags, *mappings, '-x', 'c++-module', '--precompile', path, '-o', pcm])
        mappings.append('-fmodule-file=' + name + '=' + str(pcm))
        run([compiler, *flags, *mappings, '-Wno-unused-command-line-argument', '-c', pcm, '-o', obj])
        objects.append(obj)
    sources = [lsplant / 'lsplant.cc', dex / 'dex_builder.cc', dex / 'dex_helper.cc']
    sources += [dex / 'slicer' / name for name in ('reader.cc', 'writer.cc', 'dex_ir.cc', 'common.cc',
                'dex_format.cc', 'dex_utf8.cc', 'dex_bytecode.cc', 'sha1.cpp')]
    sources.append(source / 'native/module.cpp')
    for i, path in enumerate(sources):
        obj = out / ('source-' + str(i) + '.o')
        run([compiler, *flags, *mappings, '-c', path, '-o', obj]); objects.append(obj)
    dobby = out / 'dobby'
    run([args.cmake, '-S', source / 'vendor/dobby', '-B', dobby, '-G', 'Ninja',
         '-DCMAKE_MAKE_PROGRAM=' + str(args.ninja.resolve()),
         '-DCMAKE_TOOLCHAIN_FILE=' + str(args.ndk.resolve() / 'build/cmake/android.toolchain.cmake'),
         '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-33', '-DANDROID_STL=c++_static',
         '-DCMAKE_BUILD_TYPE=Release', '-DDOBBY_DEBUG=OFF', '-DPlugin.SymbolResolver=OFF',
         '-DDOBBY_BUILD_EXAMPLE=OFF', '-DDOBBY_BUILD_TEST=OFF', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON'])
    run([args.cmake, '--build', dobby, '--target', 'dobby_static', '--parallel', '4'])
    output = out / 'libflexible_esim_transfer.so'
    run([compiler, *flags, '-shared', '-static-libstdc++', '-Wl,--gc-sections', '-Wl,-z,max-page-size=16384',
         '-Wl,--exclude-libs,ALL', '-Wl,--version-script=' + str(source / 'native/exports.map'),
         '-Wl,--no-undefined', *objects, dobby / 'libdobby.a', '-llog', '-ldl', '-lz', '-o', output])
    nm = toolchain / 'bin' / ('llvm-nm.exe' if os.name == 'nt' else 'llvm-nm')
    exports = subprocess.check_output([str(nm), '-D', '--defined-only', str(output)], text=True)
    if {line.split()[-1] for line in exports.splitlines() if line.strip()} != {'zygisk_module_entry', 'zygisk_companion_entry'}:
        raise ValueError('Unexpected native exports')
    return output
