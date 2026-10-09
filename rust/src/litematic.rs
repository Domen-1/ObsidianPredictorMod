//! Litematica schematic loading. Mirrors `RegionProxy` / `LitematicaBitArrayProxy` from
//! `src/obi_predict.py`: only the first region is used.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, BufReader, Read};
use std::path::Path;

use flate2::read::GzDecoder;

use crate::blocks::BlockState;

enum Tag {
    End,
    Byte,
    Short,
    Int(i32),
    Long,
    Float,
    Double,
    ByteArray,
    String(String),
    List(Vec<Tag>),
    /// Keeps file order, so "first region" means the same thing as in nbtlib.
    Compound(Vec<(String, Tag)>),
    IntArray,
    LongArray(Vec<u64>),
}

impl Tag {
    fn get(&self, key: &str) -> Option<&Tag> {
        match self {
            Tag::Compound(entries) => entries.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    fn take(&mut self, key: &str) -> Option<Tag> {
        match self {
            Tag::Compound(entries) => {
                let i = entries.iter().position(|(k, _)| k == key)?;
                Some(entries.swap_remove(i).1)
            }
            _ => None,
        }
    }

    fn int(&self, key: &str) -> Result<i32, String> {
        match self.get(key) {
            Some(Tag::Int(v)) => Ok(*v),
            _ => Err(format!("missing int '{key}'")),
        }
    }
}

const MAX_DEPTH: usize = 512;

struct NbtReader<R: Read> {
    r: R,
}

impl<R: Read> NbtReader<R> {
    fn bytes<const N: usize>(&mut self) -> io::Result<[u8; N]> {
        let mut buf = [0; N];
        self.r.read_exact(&mut buf)?;
        Ok(buf)
    }

    fn len(&mut self) -> io::Result<usize> {
        let len = i32::from_be_bytes(self.bytes()?);
        usize::try_from(len).map_err(|_| invalid("negative length"))
    }

    fn skip(&mut self, n: usize) -> io::Result<()> {
        let copied = io::copy(&mut (&mut self.r).take(n as u64), &mut io::sink())?;
        if copied as usize != n {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        Ok(())
    }

    fn string(&mut self) -> io::Result<String> {
        let len = u16::from_be_bytes(self.bytes()?) as usize;
        let mut buf = vec![0; len];
        self.r.read_exact(&mut buf)?;
        Ok(String::from_utf8_lossy(&buf).into_owned())
    }

    fn payload(&mut self, ty: u8, depth: usize) -> io::Result<Tag> {
        if depth > MAX_DEPTH {
            return Err(invalid("NBT nested too deeply"));
        }
        Ok(match ty {
            0 => Tag::End,
            1 => {
                self.skip(1)?;
                Tag::Byte
            }
            2 => {
                self.skip(2)?;
                Tag::Short
            }
            3 => Tag::Int(i32::from_be_bytes(self.bytes()?)),
            4 => {
                self.skip(8)?;
                Tag::Long
            }
            5 => {
                self.skip(4)?;
                Tag::Float
            }
            6 => {
                self.skip(8)?;
                Tag::Double
            }
            7 => {
                let len = self.len()?;
                self.skip(len)?;
                Tag::ByteArray
            }
            8 => Tag::String(self.string()?),
            9 => {
                let elem = self.bytes::<1>()?[0];
                let len = self.len()?;
                let mut items = Vec::new();
                for _ in 0..len {
                    items.push(self.payload(elem, depth + 1)?);
                }
                Tag::List(items)
            }
            10 => {
                let mut entries = Vec::new();
                loop {
                    let ty = self.bytes::<1>()?[0];
                    if ty == 0 {
                        break;
                    }
                    let name = self.string()?;
                    entries.push((name, self.payload(ty, depth + 1)?));
                }
                Tag::Compound(entries)
            }
            11 => {
                let len = self.len()?;
                self.skip(len.checked_mul(4).ok_or_else(|| invalid("array too long"))?)?;
                Tag::IntArray
            }
            12 => {
                let len = self.len()?;
                let mut values = Vec::with_capacity(len.min(1 << 24));
                let mut buf = vec![0u8; 8 * 8192];
                let mut remaining = len;
                while remaining > 0 {
                    let n = remaining.min(8192);
                    self.r.read_exact(&mut buf[..n * 8])?;
                    let (longs, _) = buf[..n * 8].as_chunks::<8>();
                    values.extend(longs.iter().map(|c| u64::from_be_bytes(*c)));
                    remaining -= n;
                }
                Tag::LongArray(values)
            }
            _ => return Err(invalid(&format!("unknown NBT tag type {ty}"))),
        })
    }
}

fn invalid(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.to_string())
}

pub struct BitArray {
    data: Vec<u64>,
    nbits: usize,
    mask: u64,
}

impl BitArray {
    #[inline]
    pub fn get(&self, index: usize) -> usize {
        let start_offset = index * self.nbits;
        let start = start_offset >> 6;
        let end = ((index + 1) * self.nbits - 1) >> 6;
        let bit_offset = start_offset & 0x3F;
        if start == end {
            ((self.data[start] >> bit_offset) & self.mask) as usize
        } else {
            let val = (self.data[start] >> bit_offset) | (self.data[end] << (64 - bit_offset));
            (val & self.mask) as usize
        }
    }
}

pub struct Region {
    /// Signed sizes as stored in the file; negative means the region extends backwards.
    pub width: i32,
    pub height: i32,
    pub length: i32,
    pub size_x: usize,
    pub size_y: usize,
    pub size_z: usize,
    pub palette: Vec<BlockState>,
    pub bits: BitArray,
}

impl Region {
    pub fn bits_per_block(&self) -> usize {
        self.bits.nbits
    }

    /// Palette index of the block at (x, y, z), region-local and non-negative.
    #[inline]
    pub fn get(&self, x: usize, y: usize, z: usize) -> usize {
        self.bits.get((y * self.size_x * self.size_z) + z * self.size_x + x)
    }

    pub fn from_file(path: &Path) -> Result<Region, String> {
        let file = File::open(path).map_err(|e| format!("cannot open {}: {e}", path.display()))?;
        let mut reader = NbtReader {
            r: BufReader::with_capacity(1 << 20, GzDecoder::new(BufReader::new(file))),
        };
        let read_err = |e: io::Error| format!("cannot read {}: {e}", path.display());
        let root_type = reader.bytes::<1>().map_err(read_err)?[0];
        if root_type != 10 {
            return Err("not an NBT file".into());
        }
        reader.string().map_err(read_err)?;
        let mut root = reader.payload(10, 0).map_err(read_err)?;

        let mut region = match root.take("Regions") {
            Some(Tag::Compound(mut regions)) if !regions.is_empty() => regions.swap_remove(0).1,
            Some(Tag::Compound(_)) => return Err("schematic has no regions".into()),
            _ => return Err("schematic has no 'Regions'".into()),
        };

        let mut palette = Vec::new();
        match region.get("BlockStatePalette") {
            Some(Tag::List(entries)) => {
                for entry in entries {
                    let id = match entry.get("Name") {
                        Some(Tag::String(s)) => s.clone(),
                        _ => return Err("palette entry without a name".into()),
                    };
                    let mut properties = HashMap::new();
                    if let Some(Tag::Compound(props)) = entry.get("Properties") {
                        for (k, v) in props {
                            if let Tag::String(v) = v {
                                properties.insert(k.clone(), v.clone());
                            }
                        }
                    }
                    palette.push(BlockState { id, properties });
                }
            }
            _ => return Err("region has no 'BlockStatePalette'".into()),
        }
        if palette.is_empty() {
            return Err("region palette is empty".into());
        }

        let size = region.get("Size").ok_or("region has no 'Size'")?;
        let (width, height, length) = (size.int("x")?, size.int("y")?, size.int("z")?);
        let (size_x, size_y, size_z) = (
            width.unsigned_abs() as usize,
            height.unsigned_abs() as usize,
            length.unsigned_abs() as usize,
        );

        let data = match region.take("BlockStates") {
            Some(Tag::LongArray(values)) => values,
            _ => return Err("region has no 'BlockStates'".into()),
        };
        // Same as max(ceil(log2(len(palette))), 2) in Python, without float rounding.
        let nbits = (usize::BITS - (palette.len() - 1).leading_zeros()).max(2) as usize;
        let volume = size_x * size_y * size_z;
        if data.len() * 64 < volume * nbits {
            return Err(format!(
                "'BlockStates' too short: {} longs for {volume} blocks of {nbits} bits",
                data.len()
            ));
        }

        Ok(Region {
            width,
            height,
            length,
            size_x,
            size_y,
            size_z,
            palette,
            bits: BitArray {
                data,
                nbits,
                mask: (1u64 << nbits) - 1,
            },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::BitArray;

    /// Packs values the way Litematica does: tightly, spanning across longs.
    fn pack(values: &[u64], nbits: usize) -> Vec<u64> {
        let mut data = vec![0u64; (values.len() * nbits).div_ceil(64)];
        for (i, &v) in values.iter().enumerate() {
            for b in 0..nbits {
                if v >> b & 1 == 1 {
                    let bit = i * nbits + b;
                    data[bit >> 6] |= 1 << (bit & 63);
                }
            }
        }
        data
    }

    #[test]
    fn reads_values_spanning_longs() {
        for nbits in [2, 3, 5, 7, 9, 10, 13] {
            let values: Vec<u64> = (0..500u64).map(|i| (i * 2654435761) % (1 << nbits)).collect();
            let bits = BitArray {
                data: pack(&values, nbits),
                nbits,
                mask: (1 << nbits) - 1,
            };
            for (i, &v) in values.iter().enumerate() {
                assert_eq!(bits.get(i) as u64, v, "nbits={nbits} index={i}");
            }
        }
    }
}
