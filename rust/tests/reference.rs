//! Runs the binary on the schematics shared with the mod's tests
//! (`src/test/resources/predictor`) and compares the waypoints with the Python output.
//!
//! The expected files were produced by `parity/ref_run.py ordered <file> <dir> 100 -64 -200`,
//! i.e. the original `obi_predict.py` with its sets swapped for insertion-ordered ones, which is
//! the iteration order this port (and the mod) uses. See `parity/compare.py` for how that run
//! relates to the unmodified Python version.

use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

fn check(litematic: &str, expected: &str) {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let fixtures = root.join("../src/test/resources/predictor");
    let input = fixtures.join(format!("{litematic}.litematic"));
    let work: PathBuf = std::env::temp_dir().join(format!(
        "obsidian-predictor-test-{}-{}",
        std::process::id(),
        expected
    ));
    fs::create_dir_all(&work).unwrap();

    let status = Command::new(env!("CARGO_BIN_EXE_obsidian-predictor"))
        .arg(&input)
        .args(["100", "-64", "-200"])
        .current_dir(&work)
        .status()
        .unwrap();
    assert!(status.success());

    let actual = fs::read_to_string(work.join(format!("{litematic} waypoints.txt"))).unwrap();
    let expected = fs::read_to_string(fixtures.join(format!("{expected}.txt"))).unwrap();
    fs::remove_dir_all(&work).ok();
    assert!(
        actual.replace("\r\n", "\n") == expected,
        "waypoints for {litematic} differ from the Python reference"
    );
}

#[test]
fn terrain_scan_ocean() {
    check("Terrain Scan Ocean", "terrain_scan_ocean");
}

#[test]
fn test_litematic() {
    check("test", "test");
}
