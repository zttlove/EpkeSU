use anyhow::{Context, Result};
use const_format::concatcp;
use prop_rs_android::resetprop::ResetProp;
use prop_rs_android::sys_prop;
use serde_json::json;
use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use crate::{defs, utils};

const CONFIG_PATH: &str = concatcp!(defs::WORKING_DIR, ".epkesu_hide");
const BACKUP_PATH: &str = concatcp!(defs::WORKING_DIR, ".epkesu_hide_props");

const RESET_PROPS: &[(&str, &str)] = &[
    ("ro.boot.vbmeta.device_state", "locked"),
    ("ro.boot.verifiedbootstate", "green"),
    ("ro.boot.flash.locked", "1"),
    ("ro.boot.veritymode", "enforcing"),
    ("ro.boot.warranty_bit", "0"),
    ("ro.warranty_bit", "0"),
    ("ro.debuggable", "0"),
    ("ro.force.debuggable", "0"),
    ("ro.secure", "1"),
    ("ro.adb.secure", "1"),
    ("ro.build.type", "user"),
    ("ro.build.tags", "release-keys"),
    ("ro.vendor.boot.warranty_bit", "0"),
    ("ro.vendor.warranty_bit", "0"),
    ("vendor.boot.vbmeta.device_state", "locked"),
    ("vendor.boot.verifiedbootstate", "green"),
    ("sys.oem_unlock_allowed", "0"),
    ("ro.secureboot.lockstate", "locked"),
    ("ro.boot.realmebootstate", "green"),
    ("ro.boot.realme.lockstate", "1"),
];

const CONTAINS_PROPS: &[(&str, &str, &str)] = &[
    ("ro.bootmode", "recovery", "unknown"),
    ("ro.boot.bootmode", "recovery", "unknown"),
    ("vendor.boot.bootmode", "recovery", "unknown"),
];

const fn resetprop() -> ResetProp {
    ResetProp {
        skip_svc: true,
        persistent: false,
        persist_only: false,
        verbose: false,
        show_context: false,
        rebuild: false,
    }
}

pub fn is_enabled() -> bool {
    fs::read_to_string(CONFIG_PATH).is_ok_and(|content| content.trim() == "1")
}

pub fn print_status() {
    println!(
        "{}",
        json!({
            "enabled": is_enabled(),
        })
    );
}

pub fn enable() -> Result<()> {
    clear_backup();
    if let Err(err) = apply() {
        if let Err(restore_err) = restore_backup() {
            log::warn!("epkesu-hide: rollback failed after enable error: {restore_err:#}");
        }
        return Err(err);
    }
    write_enabled(true)
}

pub fn disable() -> Result<()> {
    restore_backup()?;
    write_enabled(false)
}

pub fn apply_if_enabled() {
    if is_enabled() {
        clear_backup();
        if let Err(err) = apply() {
            log::warn!("epkesu-hide: apply failed: {err:#}");
        }
    }
}

pub fn apply() -> Result<()> {
    sys_prop::init().context("Failed to initialize system property API")?;

    let rp = resetprop();
    let mut backup = read_backup()?;
    let mut backup_changed = false;

    for (name, expected) in RESET_PROPS {
        backup_changed |= reset_prop_if_needed(&rp, &mut backup, name, expected)?;
    }

    for (name, contains, new_value) in CONTAINS_PROPS {
        backup_changed |= reset_prop_if_contains(&rp, &mut backup, name, contains, new_value)?;
    }

    if backup_changed {
        write_backup(&backup)?;
    }

    Ok(())
}

fn write_enabled(enabled: bool) -> Result<()> {
    utils::ensure_dir_exists(Path::new(defs::WORKING_DIR))?;
    fs::write(CONFIG_PATH, if enabled { "1\n" } else { "0\n" })
        .with_context(|| format!("failed to write {CONFIG_PATH}"))
}

fn reset_prop_if_needed(
    rp: &ResetProp,
    backup: &mut BTreeMap<String, String>,
    name: &str,
    expected: &str,
) -> Result<bool> {
    let Some(value) = rp.get(name) else {
        return Ok(false);
    };

    if value.is_empty() || value == expected {
        return Ok(false);
    }

    let backup_changed = backup_original(backup, name, &value);
    if backup_changed {
        write_backup(backup)?;
    }
    rp.set(name, expected)
        .with_context(|| format!("Failed to set {name}"))?;
    Ok(backup_changed)
}

fn reset_prop_if_contains(
    rp: &ResetProp,
    backup: &mut BTreeMap<String, String>,
    name: &str,
    contains: &str,
    new_value: &str,
) -> Result<bool> {
    let Some(value) = rp.get(name) else {
        return Ok(false);
    };

    if !value.contains(contains) {
        return Ok(false);
    }

    let backup_changed = backup_original(backup, name, &value);
    if backup_changed {
        write_backup(backup)?;
    }
    rp.set(name, new_value)
        .with_context(|| format!("Failed to set {name}"))?;
    Ok(backup_changed)
}

fn backup_original(backup: &mut BTreeMap<String, String>, name: &str, value: &str) -> bool {
    if backup.contains_key(name) {
        return false;
    }

    backup.insert(name.to_owned(), value.to_owned());
    true
}

fn restore_backup() -> Result<()> {
    let backup = read_backup()?;
    if backup.is_empty() {
        return Ok(());
    }

    sys_prop::init().context("Failed to initialize system property API")?;
    let rp = resetprop();
    for (name, value) in &backup {
        rp.set(name, value)
            .with_context(|| format!("Failed to restore {name}"))?;
    }
    clear_backup();
    Ok(())
}

fn clear_backup() {
    _ = fs::remove_file(BACKUP_PATH);
}

fn read_backup() -> Result<BTreeMap<String, String>> {
    let path = Path::new(BACKUP_PATH);
    if !path.exists() {
        return Ok(BTreeMap::new());
    }

    let content =
        fs::read_to_string(path).with_context(|| format!("failed to read {}", path.display()))?;
    let mut backup = BTreeMap::new();
    for line in content.lines() {
        let Some((name, value)) = line.split_once('=') else {
            continue;
        };
        if !name.is_empty() {
            backup.insert(name.to_owned(), value.to_owned());
        }
    }
    Ok(backup)
}

fn write_backup(backup: &BTreeMap<String, String>) -> Result<()> {
    utils::ensure_dir_exists(Path::new(defs::WORKING_DIR))?;

    let mut content = String::new();
    for (name, value) in backup {
        content.push_str(name);
        content.push('=');
        content.push_str(value);
        content.push('\n');
    }

    fs::write(BACKUP_PATH, content).with_context(|| format!("failed to write {BACKUP_PATH}"))
}
