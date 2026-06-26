use std::env;
use std::fs::{self, File};
use std::io::Write;
use std::path::Path;
use std::process::Command;

fn get_git_version() -> Result<(u32, String), std::io::Error> {
    let output = Command::new("git")
        .args(["rev-list", "--count", "HEAD"])
        .output()?;

    let output = output.stdout;
    let version_code = String::from_utf8(output).expect("Failed to read git count stdout");
    let version_code: u32 = version_code
        .trim()
        .parse()
        .map_err(|_| std::io::Error::other("Failed to parse git count"))?;
    let version_code = 30000 + version_code;

    let version_name = String::from_utf8(
        Command::new("git")
            .args(["describe", "--tags", "--always"])
            .output()?
            .stdout,
    )
    .map_err(|_| std::io::Error::other("Failed to read git describe stdout"))?;
    let version_name = version_name.trim_start_matches('v').to_string();
    Ok((version_code, version_name))
}

fn get_fallback_version() -> Option<(u32, String)> {
    let manifest_dir = env::var("CARGO_MANIFEST_DIR").ok()?;
    let version_file = Path::new(&manifest_dir).join("../../version.properties");
    println!("cargo:rerun-if-changed={}", version_file.display());
    let content = fs::read_to_string(version_file).ok()?;

    let mut code = None;
    let mut name = None;

    for line in content.lines().map(str::trim) {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key.trim() {
            "versionCode" => code = value.trim().parse().ok(),
            "versionName" => name = Some(value.trim().to_string()),
            _ => {}
        }
    }

    code.zip(name)
}

fn configure_bindgen() {
    // The bindgen::Builder is the main entry point
    // to bindgen, and lets you build up options for
    // the resulting bindings.
    let bindings = bindgen::Builder::default()
        // The input header we would like to generate
        // bindings for.
        .header("src/ksu_uapi.h")
        .clang_args(["-x", "c++", "-I../../"])
        // Tell cargo to invalidate the built crate whenever any of the
        // included header files changed.
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
        // Finish the builder and generate the bindings.
        .generate()
        // Unwrap the Result and panic on failure.
        .expect("Unable to generate bindings");

    // Write the bindings to the $OUT_DIR/bindings.rs file.
    let out_path = std::path::PathBuf::from(env::var("OUT_DIR").unwrap());
    // for debug, uncomment below
    // let out_path = std::path::PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    bindings
        .write_to_file(out_path.join("bindings.rs"))
        .expect("Couldn't write bindings!");
}

fn assert_release_assets() {
    if env::var("PROFILE").as_deref() != Ok("release") {
        return;
    }

    let target_arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    let asset_dir = if target_arch == "x86_64" && target_os == "android" {
        "bin/x86_64"
    } else {
        "bin/aarch64"
    };

    if asset_dir != "bin/aarch64" {
        return;
    }

    let manifest_dir = env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR not set");
    let ksuinit = Path::new(&manifest_dir).join(asset_dir).join("ksuinit");
    println!("cargo:rerun-if-changed={}", ksuinit.display());

    assert!(
        ksuinit.is_file(),
        "missing {}; run `just build_ksud` or build ksuinit with \
         `cross build --package ksuinit --target aarch64-unknown-linux-musl --release`",
        ksuinit.display()
    );
}

fn main() {
    let (code, name) = match get_fallback_version() {
        Some((code, name)) => (code, name),
        None => match get_git_version() {
            Ok((code, name)) => (code, name),
            Err(_) => {
                // show warning if git is not installed
                println!("cargo:warning=Failed to get git version, using 0.0.0");
                (0, "0.0.0".to_string())
            }
        },
    };

    assert_release_assets();

    let out_dir = env::var("OUT_DIR").expect("Failed to get $OUT_DIR");
    let out_dir = Path::new(&out_dir);
    File::create(Path::new(out_dir).join("VERSION_CODE"))
        .expect("Failed to create VERSION_CODE")
        .write_all(code.to_string().as_bytes())
        .expect("Failed to write VERSION_CODE");

    File::create(Path::new(out_dir).join("VERSION_NAME"))
        .expect("Failed to create VERSION_NAME")
        .write_all(name.trim().as_bytes())
        .expect("Failed to write VERSION_NAME");

    let target_os = env::var("CARGO_CFG_TARGET_OS").expect("CARGO_CFG_TARGET_OS not set");
    if target_os == "android" {
        configure_bindgen();
    }
}
