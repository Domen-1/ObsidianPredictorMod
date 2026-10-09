//! Console front end with the same flow as `src/ui.py`: pick a litematic, get `Corner 1`
//! from the file name or ask for it, simulate, write `<name> waypoints.txt` to the current
//! directory.
//!
//! Usage: obsidian-predictor [FILE.litematic [X Y Z]]
//! Without arguments a file dialog opens (double-click / drag-and-drop on the executable).

mod blocks;
mod litematic;
mod predictor;

use std::ffi::OsString;
use std::fs;
use std::io::{self, BufRead, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use crate::blocks::Classifier;
use crate::litematic::Region;
use crate::predictor::ObsidianPredictor;

const NEWLINE: &str = if cfg!(windows) { "\r\n" } else { "\n" };

/// Last three space-separated tokens of the file name, e.g. `Terrain Scan 42 -64 42.litematic`.
fn parse_area_origin(name: &str) -> Option<(i64, i64, i64)> {
    let tokens: Vec<&str> = name.split_whitespace().collect();
    if tokens.len() < 3 {
        return None;
    }
    let coords: Vec<i64> = tokens[tokens.len() - 3..]
        .iter()
        .map(|t| t.parse().ok())
        .collect::<Option<_>>()?;
    Some((coords[0], coords[1], coords[2]))
}

fn request_coordinates(text: &str) -> Result<(i64, i64, i64), String> {
    println!("{text}");
    let stdin = io::stdin();
    let read_axis = |axis: &str| -> Result<i64, String> {
        loop {
            print!("{axis}: ");
            io::stdout().flush().ok();
            let mut line = String::new();
            if stdin.lock().read_line(&mut line).map_err(|e| e.to_string())? == 0 {
                return Err("no coordinates entered".into());
            }
            match line.trim().parse() {
                Ok(v) => return Ok(v),
                Err(_) => println!("Please enter valid integers"),
            }
        }
    };
    Ok((read_axis("X")?, read_axis("Y")?, read_axis("Z")?))
}

fn pick_file() -> Option<PathBuf> {
    rfd::FileDialog::new()
        .add_filter("Litematica", &["litematic"])
        .pick_file()
}

fn run(args: &[OsString]) -> Result<(), String> {
    let path = match args.first() {
        Some(p) => PathBuf::from(p),
        None => match pick_file() {
            Some(p) => p,
            None => {
                println!("Terminating...");
                return Ok(());
            }
        },
    };
    let name = Path::new(path.file_name().unwrap_or(path.as_os_str()))
        .file_stem()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned();

    let origin = match &args[1.min(args.len())..] {
        [] => None,
        [x, y, z] => {
            let parse = |v: &OsString| {
                v.to_string_lossy()
                    .parse::<i64>()
                    .map_err(|_| format!("invalid coordinate '{}'", v.to_string_lossy()))
            };
            Some((parse(x)?, parse(y)?, parse(z)?))
        }
        _ => return Err("usage: obsidian-predictor [FILE.litematic [X Y Z]]".into()),
    };

    let started = Instant::now();
    let region = Region::from_file(&path)?;
    let loaded = started.elapsed();

    let (x0, y0, z0) = match origin.or_else(|| parse_area_origin(&name)) {
        Some(origin) => origin,
        None => request_coordinates("Input [Corner 1] of your selection")?,
    };

    let classifier = Classifier::new();
    let mut predictor = ObsidianPredictor::new(x0, y0, z0, &region, &classifier)?;
    println!(
        "Removing layers from '{name}' ({}x{}x{}) and simulating liquids...",
        predictor.size_x, predictor.size_y, predictor.size_z
    );
    let simulate_started = Instant::now();
    let mut last_shown = -1;
    predictor.run(|done| {
        let permille = (done * 1000.0) as i32;
        if permille != last_shown {
            last_shown = permille;
            eprint!("\r{:.1} %", done * 100.0);
        }
    })?;
    eprintln!();
    let simulated = simulate_started.elapsed();

    let lines = predictor.waypoints();
    let out_path = format!("{name} waypoints.txt");
    let mut contents = String::with_capacity(lines.len() * 72);
    for line in &lines {
        contents.push_str(line);
        contents.push_str(NEWLINE);
    }
    fs::write(&out_path, contents).map_err(|e| format!("cannot write {out_path}: {e}"))?;

    let lava = predictor.lava_marker_count();
    println!(
        "Saved {} waypoints ({lava} lava, {} blast resistant) to '{out_path}'",
        lines.len(),
        lines.len() - lava
    );
    println!(
        "Loaded in {:.2} s, simulated in {:.2} s",
        loaded.as_secs_f64(),
        simulated.as_secs_f64()
    );
    Ok(())
}

fn main() -> ExitCode {
    let args: Vec<OsString> = std::env::args_os().skip(1).collect();
    // Double-click or drag-and-drop on Windows: keep the console window open at the end.
    let pause_at_exit = cfg!(windows) && args.len() <= 1;
    let result = run(&args);
    if let Err(e) = &result {
        eprintln!("Error: {e}");
    }
    if pause_at_exit {
        print!("Press Enter to exit...");
        io::stdout().flush().ok();
        let _ = io::stdin().lock().read_line(&mut String::new());
    }
    if result.is_ok() { ExitCode::SUCCESS } else { ExitCode::FAILURE }
}

#[cfg(test)]
mod tests {
    use super::parse_area_origin;

    #[test]
    fn origin_from_file_name() {
        assert_eq!(parse_area_origin("Terrain Scan 42 -64 42"), Some((42, -64, 42)));
        assert_eq!(parse_area_origin("1 2 3"), Some((1, 2, 3)));
        assert_eq!(parse_area_origin("Terrain Scan Ocean"), None);
        assert_eq!(parse_area_origin("scan 1 2"), None);
        assert_eq!(parse_area_origin("1 2"), None);
    }
}
