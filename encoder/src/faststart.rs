//! Moves an MP4's index (`moov`) in front of its media data (`mdat`), adjusting every chunk
//! offset (`stco` and `co64`, all tracks), through a temp file that then replaces the original.
//!
//! Used on Windows: Media Foundation's own MF_MPEG4SINK_MOOV_BEFORE_MDAT broke the file on the
//! Windows box (Finalize failed with E_ACCESSDENIED, or with our own byte stream the file came out
//! with `moov` first and `mdat` half moved). AVFoundation's output already has the index first.
//!
//! On any failure neither the file nor the temp file is left: a broken file is never emitted.
#![cfg_attr(not(windows), allow(dead_code))]

use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    AlreadyFirst,
    Moved,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IndexError {
    /// The file is not laid out as expected (truncated, no or two `moov`, bad table sizes, ...).
    Layout,
    /// Reading or writing failed.
    Io,
    /// The caller asked to stop.
    Cancelled,
}

impl IndexError {
    pub fn code(self) -> &'static str {
        match self {
            IndexError::Layout => "index_layout",
            IndexError::Io => "index_io",
            IndexError::Cancelled => "cancelled",
        }
    }
}

/// The temp file used while rewriting `path`: same folder, still ending in `.partial.mp4`, so the
/// mod's stale-partial cleanup also finds it after a crash.
pub fn temp_path(path: &Path) -> PathBuf {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let stem = if name.to_ascii_lowercase().ends_with(".mp4") { &name[..name.len() - 4] } else { &name[..] };
    path.with_file_name(format!("{stem}.ix.partial.mp4"))
}

/// Rewrites `path` with `moov` first. `cancelled` is polled between copied chunks.
pub fn move_index_first(path: &Path, cancelled: &dyn Fn() -> bool) -> Result<Outcome, IndexError> {
    let temp = temp_path(path);
    let result = rewrite(path, &temp, cancelled);
    if result.is_err() {
        let _ = std::fs::remove_file(&temp);
        let _ = std::fs::remove_file(path);
    }
    result
}

/// Largest index we load into memory.
const MAX_MOOV: u64 = 256 << 20;
const COPY_CHUNK: usize = 8 << 20;

struct TopBox {
    kind: [u8; 4],
    offset: u64,
    size: u64,
}

fn io<T>(r: std::io::Result<T>) -> Result<T, IndexError> {
    r.map_err(|_| IndexError::Io)
}

fn top_level_boxes(file: &mut File, len: u64) -> Result<Vec<TopBox>, IndexError> {
    let mut boxes = Vec::new();
    let mut offset = 0u64;
    while offset < len {
        if len - offset < 8 {
            return Err(IndexError::Layout);
        }
        let mut head = [0u8; 16];
        io(file.seek(SeekFrom::Start(offset)))?;
        let want = (len - offset).min(16) as usize;
        io(file.read_exact(&mut head[..want]))?;
        let small = u32::from_be_bytes([head[0], head[1], head[2], head[3]]) as u64;
        let kind = [head[4], head[5], head[6], head[7]];
        let size = match small {
            0 => len - offset,
            1 if want == 16 => u64::from_be_bytes(head[8..16].try_into().unwrap()),
            1 => return Err(IndexError::Layout),
            n => n,
        };
        let header = if small == 1 { 16 } else { 8 };
        if size < header || size > len - offset {
            return Err(IndexError::Layout);
        }
        boxes.push(TopBox { kind, offset, size });
        offset += size;
    }
    Ok(boxes)
}

fn rewrite(path: &Path, temp: &Path, cancelled: &dyn Fn() -> bool) -> Result<Outcome, IndexError> {
    let mut src = io(File::open(path))?;
    let len = io(src.metadata())?.len();
    let boxes = top_level_boxes(&mut src, len)?;
    let mut moovs = boxes.iter().filter(|b| &b.kind == b"moov");
    let moov = moovs.next().ok_or(IndexError::Layout)?;
    if moovs.next().is_some() {
        return Err(IndexError::Layout);
    }
    let first_mdat = boxes.iter().find(|b| &b.kind == b"mdat").ok_or(IndexError::Layout)?;
    if moov.offset < first_mdat.offset {
        return Ok(Outcome::AlreadyFirst);
    }
    if moov.size > MAX_MOOV {
        return Err(IndexError::Layout);
    }
    let mut old = vec![0u8; moov.size as usize];
    io(src.seek(SeekFrom::Start(moov.offset)))?;
    io(src.read_exact(&mut old))?;

    // New layout: [0, first_mdat) moov [first_mdat, moov) [moov_end, len).
    let (start, moov_at, moov_end) = (first_mdat.offset, moov.offset, moov.offset + moov.size);
    let old_len = moov.size;
    let shift = |offset: u64, new_len: u64| -> Result<u64, IndexError> {
        if offset < start {
            Ok(offset)
        } else if offset < moov_at {
            Ok(offset + new_len)
        } else if offset < moov_end {
            Err(IndexError::Layout)
        } else {
            Ok(offset + new_len - old_len)
        }
    };
    let new_moov = rebuild_moov(&old, &shift)?;

    let mut out = io(OpenOptions::new().write(true).create(true).truncate(true).open(temp))?;
    let mut buf = vec![0u8; COPY_CHUNK];
    copy_range(&mut src, &mut out, 0, start, &mut buf, cancelled)?;
    io(out.write_all(&new_moov))?;
    copy_range(&mut src, &mut out, start, moov_at, &mut buf, cancelled)?;
    copy_range(&mut src, &mut out, moov_end, len, &mut buf, cancelled)?;
    if cancelled() {
        return Err(IndexError::Cancelled);
    }
    io(out.sync_all())?;
    drop(out);
    drop(src);
    io(std::fs::rename(temp, path))?;
    Ok(Outcome::Moved)
}

fn copy_range(
    src: &mut File,
    out: &mut File,
    from: u64,
    to: u64,
    buf: &mut [u8],
    cancelled: &dyn Fn() -> bool,
) -> Result<(), IndexError> {
    io(src.seek(SeekFrom::Start(from)))?;
    let mut left = to - from;
    while left > 0 {
        if cancelled() {
            return Err(IndexError::Cancelled);
        }
        let n = left.min(buf.len() as u64) as usize;
        io(src.read_exact(&mut buf[..n]))?;
        io(out.write_all(&buf[..n]))?;
        left -= n as u64;
    }
    Ok(())
}

/// Boxes on the way from `moov` to the chunk offset tables.
const CONTAINERS: [&[u8; 4]; 5] = [b"moov", b"trak", b"mdia", b"minf", b"stbl"];

enum Node {
    Container([u8; 4], Vec<Node>),
    Offsets { flags: [u8; 4], wide: bool, entries: Vec<u64> },
    Leaf([u8; 4], Vec<u8>),
}

fn parse(data: &[u8]) -> Result<Vec<Node>, IndexError> {
    let mut nodes = Vec::new();
    let mut o = 0usize;
    while o < data.len() {
        if data.len() - o < 8 {
            return Err(IndexError::Layout);
        }
        let small = u32::from_be_bytes(data[o..o + 4].try_into().unwrap()) as u64;
        let kind: [u8; 4] = data[o + 4..o + 8].try_into().unwrap();
        let (size, header) = match small {
            0 => return Err(IndexError::Layout),
            1 => {
                if data.len() - o < 16 {
                    return Err(IndexError::Layout);
                }
                (u64::from_be_bytes(data[o + 8..o + 16].try_into().unwrap()), 16usize)
            }
            n => (n, 8usize),
        };
        if size < header as u64 || size > (data.len() - o) as u64 {
            return Err(IndexError::Layout);
        }
        let body = &data[o + header..o + size as usize];
        nodes.push(if CONTAINERS.contains(&&kind) {
            Node::Container(kind, parse(body)?)
        } else if &kind == b"stco" || &kind == b"co64" {
            let wide = &kind == b"co64";
            let width = if wide { 8 } else { 4 };
            if body.len() < 8 {
                return Err(IndexError::Layout);
            }
            let count = u32::from_be_bytes(body[4..8].try_into().unwrap()) as usize;
            if body.len() != 8 + count * width {
                return Err(IndexError::Layout);
            }
            let entries = body[8..]
                .chunks_exact(width)
                .map(|c| if wide { u64::from_be_bytes(c.try_into().unwrap()) } else { u32::from_be_bytes(c.try_into().unwrap()) as u64 })
                .collect();
            Node::Offsets { flags: body[0..4].try_into().unwrap(), wide, entries }
        } else {
            Node::Leaf(kind, body.to_vec())
        });
        o += size as usize;
    }
    Ok(nodes)
}

fn body_len(node: &Node) -> u64 {
    match node {
        Node::Container(_, kids) => kids.iter().map(node_len).sum(),
        Node::Offsets { wide, entries, .. } => 8 + entries.len() as u64 * if *wide { 8 } else { 4 },
        Node::Leaf(_, body) => body.len() as u64,
    }
}

fn node_len(node: &Node) -> u64 {
    let body = body_len(node);
    if body + 8 > u32::MAX as u64 { body + 16 } else { body + 8 }
}

fn write_node(node: &Node, out: &mut Vec<u8>, shift: &dyn Fn(u64) -> Result<u64, IndexError>) -> Result<(), IndexError> {
    let body = body_len(node);
    let kind = match node {
        Node::Container(k, _) | Node::Leaf(k, _) => *k,
        Node::Offsets { wide: true, .. } => *b"co64",
        Node::Offsets { wide: false, .. } => *b"stco",
    };
    if body + 8 > u32::MAX as u64 {
        out.extend_from_slice(&1u32.to_be_bytes());
        out.extend_from_slice(&kind);
        out.extend_from_slice(&(body + 16).to_be_bytes());
    } else {
        out.extend_from_slice(&((body + 8) as u32).to_be_bytes());
        out.extend_from_slice(&kind);
    }
    match node {
        Node::Container(_, kids) => {
            for k in kids {
                write_node(k, out, shift)?;
            }
        }
        Node::Offsets { flags, wide, entries } => {
            out.extend_from_slice(flags);
            out.extend_from_slice(&(entries.len() as u32).to_be_bytes());
            for &e in entries {
                let v = shift(e)?;
                if *wide {
                    out.extend_from_slice(&v.to_be_bytes());
                } else {
                    let v = u32::try_from(v).map_err(|_| IndexError::Layout)?;
                    out.extend_from_slice(&v.to_be_bytes());
                }
            }
        }
        Node::Leaf(_, body) => out.extend_from_slice(body),
    }
    Ok(())
}

/// Widens every 32-bit table whose shifted values exceed 32 bits; true if any changed.
fn widen(nodes: &mut [Node], shift: &dyn Fn(u64) -> Result<u64, IndexError>) -> Result<bool, IndexError> {
    let mut changed = false;
    for node in nodes {
        match node {
            Node::Container(_, kids) => changed |= widen(kids, shift)?,
            Node::Offsets { wide, entries, .. } if !*wide => {
                for &e in entries.iter() {
                    if shift(e)? > u32::MAX as u64 {
                        *wide = true;
                        changed = true;
                        break;
                    }
                }
            }
            _ => {}
        }
    }
    Ok(changed)
}

/// Rebuilds a `moov` box (whole box, header included) with every chunk offset passed through
/// `shift`. The shift may depend on the rebuilt length (`shift(offset, new_moov_len)`); a 32-bit
/// table whose shifted values no longer fit becomes a 64-bit one.
pub fn rebuild_moov(
    moov: &[u8],
    shift: &dyn Fn(u64, u64) -> Result<u64, IndexError>,
) -> Result<Vec<u8>, IndexError> {
    let mut nodes = parse(moov)?;
    if nodes.len() != 1 || !matches!(&nodes[0], Node::Container(k, _) if k == b"moov") {
        return Err(IndexError::Layout);
    }
    // Widening grows the box, which can push more offsets past 32 bits: repeat until stable.
    loop {
        let len = node_len(&nodes[0]);
        if !widen(&mut nodes, &|o| shift(o, len))? {
            break;
        }
    }
    let len = node_len(&nodes[0]);
    let mut out = Vec::with_capacity(len as usize);
    write_node(&nodes[0], &mut out, &|o| shift(o, len))?;
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;

    fn bx(kind: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let mut v = ((payload.len() + 8) as u32).to_be_bytes().to_vec();
        v.extend_from_slice(kind);
        v.extend_from_slice(payload);
        v
    }

    fn stco(entries: &[u32]) -> Vec<u8> {
        let mut p = vec![0, 0, 0, 0];
        p.extend_from_slice(&(entries.len() as u32).to_be_bytes());
        for e in entries {
            p.extend_from_slice(&e.to_be_bytes());
        }
        bx(b"stco", &p)
    }

    fn co64(entries: &[u64]) -> Vec<u8> {
        let mut p = vec![0, 0, 0, 0];
        p.extend_from_slice(&(entries.len() as u32).to_be_bytes());
        for e in entries {
            p.extend_from_slice(&e.to_be_bytes());
        }
        bx(b"co64", &p)
    }

    fn trak(table: Vec<u8>) -> Vec<u8> {
        let stbl = bx(b"stbl", &[bx(b"stsd", &[0; 16]), table].concat());
        let minf = bx(b"minf", &[bx(b"vmhd", &[0; 12]), stbl].concat());
        let mdia = bx(b"mdia", &[bx(b"mdhd", &[0; 24]), minf].concat());
        bx(b"trak", &[bx(b"tkhd", &[0; 84]), mdia].concat())
    }

    fn moov(tables: Vec<Vec<u8>>) -> Vec<u8> {
        let mut inner = bx(b"mvhd", &[0; 100]);
        for t in tables {
            inner.extend(trak(t));
        }
        bx(b"moov", &inner)
    }

    /// ftyp, uuid, mdat (chunks marked "A0".."B1" at known offsets), moov last.
    /// Returns (file, chunk offsets of track A, of track B, marker bytes per chunk).
    fn index_last_file() -> (Vec<u8>, Vec<u64>, Vec<u64>) {
        let ftyp = bx(b"ftyp", b"mp42\0\0\0\0isommp42");
        let uuid = bx(b"uuid", &[7; 31]);
        let head = ftyp.len() + uuid.len();
        let mut payload = Vec::new();
        let mut a = vec![];
        let mut b = vec![];
        for (i, tag) in [b"A0", b"B0", b"A1", b"B1"].iter().enumerate() {
            let off = (head + 8 + payload.len()) as u64;
            if i % 2 == 0 { a.push(off) } else { b.push(off) }
            payload.extend_from_slice(*tag);
            payload.extend_from_slice(&[i as u8; 50]);
        }
        let mdat = bx(b"mdat", &payload);
        let m = moov(vec![stco(&a.iter().map(|&o| o as u32).collect::<Vec<_>>()), co64(&b)]);
        ([ftyp, uuid, mdat, m].concat(), a, b)
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("cobblify-faststart-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir.join("x.partial.mp4")
    }

    fn top_level(file: &[u8]) -> Vec<(String, usize, usize)> {
        let mut out = vec![];
        let mut o = 0;
        while o + 8 <= file.len() {
            let n = u32::from_be_bytes(file[o..o + 4].try_into().unwrap()) as usize;
            out.push((String::from_utf8_lossy(&file[o + 4..o + 8]).into_owned(), o, n));
            o += n;
        }
        out
    }

    fn find_all(data: &[u8], kind: &[u8; 4]) -> Vec<usize> {
        (0..data.len().saturating_sub(4)).filter(|&i| &data[i..i + 4] == kind).collect()
    }

    fn table_entries(file: &[u8], kind: &[u8; 4]) -> Vec<u64> {
        let at = find_all(file, kind)[0];
        let count = u32::from_be_bytes(file[at + 8..at + 12].try_into().unwrap()) as usize;
        let w = if kind == b"co64" { 8 } else { 4 };
        (0..count)
            .map(|i| {
                let s = &file[at + 12 + i * w..at + 12 + (i + 1) * w];
                if w == 8 { u64::from_be_bytes(s.try_into().unwrap()) } else { u32::from_be_bytes(s.try_into().unwrap()) as u64 }
            })
            .collect()
    }

    #[test]
    fn moves_moov_first_and_shifts_both_table_kinds_in_every_track() {
        let path = scratch("move");
        let (file, a, b) = index_last_file();
        std::fs::write(&path, &file).unwrap();
        assert_eq!(move_index_first(&path, &|| false), Ok(Outcome::Moved));
        let out = std::fs::read(&path).unwrap();
        assert_eq!(out.len(), file.len());
        let names: Vec<String> = top_level(&out).into_iter().map(|t| t.0).collect();
        assert_eq!(names, ["ftyp", "uuid", "moov", "mdat"]);
        let moov_len = top_level(&out)[2].2 as u64;
        let new_a = table_entries(&out, b"stco");
        let new_b = table_entries(&out, b"co64");
        assert_eq!(new_a, a.iter().map(|o| o + moov_len).collect::<Vec<_>>());
        assert_eq!(new_b, b.iter().map(|o| o + moov_len).collect::<Vec<_>>());
        for (o, tag) in new_a.iter().zip([b"A0", b"A1"]).chain(new_b.iter().zip([b"B0", b"B1"])) {
            assert_eq!(&out[*o as usize..*o as usize + 2], tag, "chunk at {o}");
        }
        assert!(!temp_path(&path).exists());
    }

    #[test]
    fn a_file_with_moov_first_is_left_alone() {
        let path = scratch("first");
        let (file, _, _) = index_last_file();
        let t = top_level(&file);
        let (mdat, moov) = (&file[t[2].1..t[2].1 + t[2].2], &file[t[3].1..]);
        let first = [&file[..t[2].1], moov, mdat].concat();
        std::fs::write(&path, &first).unwrap();
        assert_eq!(move_index_first(&path, &|| false), Ok(Outcome::AlreadyFirst));
        assert_eq!(std::fs::read(&path).unwrap(), first);
        assert!(!temp_path(&path).exists());
    }

    fn assert_fails_and_removes(name: &str, file: &[u8], want: IndexError) {
        let path = scratch(name);
        std::fs::write(&path, file).unwrap();
        assert_eq!(move_index_first(&path, &|| false), Err(want), "{name}");
        assert!(!path.exists(), "{name}: broken file removed");
        assert!(!temp_path(&path).exists(), "{name}: temp removed");
    }

    #[test]
    fn truncated_or_unexpected_layouts_fail_and_leave_no_file() {
        let (file, _, _) = index_last_file();
        assert_fails_and_removes("truncated", &file[..file.len() - 10], IndexError::Layout);
        let t = top_level(&file);
        assert_fails_and_removes("no-moov", &file[..t[3].1], IndexError::Layout);
        let two = [&file[..], &file[t[3].1..]].concat();
        assert_fails_and_removes("two-moov", &two, IndexError::Layout);
        let no_mdat = [&file[..t[2].1], &file[t[3].1..]].concat();
        assert_fails_and_removes("no-mdat", &no_mdat, IndexError::Layout);
        let mut tiny = file.clone();
        tiny[t[1].1..t[1].1 + 4].copy_from_slice(&4u32.to_be_bytes());
        assert_fails_and_removes("box-too-small", &tiny, IndexError::Layout);
        // stco claims more entries than it holds.
        let mut bad = file.clone();
        let at = find_all(&bad, b"stco")[0];
        bad[at + 8..at + 12].copy_from_slice(&9u32.to_be_bytes());
        assert_fails_and_removes("bad-count", &bad, IndexError::Layout);
        // A chunk offset that points into the index itself.
        let mut inside = file.clone();
        let at = find_all(&inside, b"stco")[0];
        let into_moov = (t[3].1 + 20) as u32;
        inside[at + 12..at + 16].copy_from_slice(&into_moov.to_be_bytes());
        assert_fails_and_removes("offset-in-moov", &inside, IndexError::Layout);
        assert_fails_and_removes("empty", &[], IndexError::Layout);
    }

    #[test]
    fn cancel_during_the_rewrite_leaves_no_file() {
        let path = scratch("cancel");
        let (file, _, _) = index_last_file();
        std::fs::write(&path, &file).unwrap();
        let polls = Cell::new(0);
        let result = move_index_first(&path, &|| {
            polls.set(polls.get() + 1);
            true
        });
        assert_eq!(result, Err(IndexError::Cancelled));
        assert!(polls.get() >= 1);
        assert!(!path.exists());
        assert!(!temp_path(&path).exists());
    }

    #[test]
    fn a_32_bit_table_that_overflows_becomes_64_bit() {
        let m = moov(vec![stco(&[100, 200]), co64(&[300])]);
        let big = u32::MAX as u64 - 50; // 100 + big and 200 + big no longer fit in 32 bits
        let out = rebuild_moov(&m, &|o, _| Ok(o + big)).unwrap();
        assert_eq!(&out[4..8], b"moov");
        assert_eq!(u32::from_be_bytes(out[0..4].try_into().unwrap()) as usize, out.len());
        assert!(find_all(&out, b"stco").is_empty(), "stco upgraded");
        let tables = find_all(&out, b"co64");
        assert_eq!(tables.len(), 2);
        let first = table_entries(&out[tables[0] - 4..], b"co64");
        assert_eq!(first, vec![100 + big, 200 + big]);
        let second = table_entries(&out[tables[1] - 4..], b"co64");
        assert_eq!(second, vec![300 + big]);
        // Parents grew by 8 bytes (two entries 4 -> 8 bytes each): every container size is right.
        assert_eq!(out.len(), m.len() + 8);
        for kind in [b"trak", b"mdia", b"minf", b"stbl"] {
            for at in find_all(&out, kind) {
                let size = u32::from_be_bytes(out[at - 4..at].try_into().unwrap()) as usize;
                assert!(at - 4 + size <= out.len(), "{} fits", String::from_utf8_lossy(kind));
            }
        }
    }

    #[test]
    fn rebuild_passes_the_new_length_to_the_shift() {
        let m = moov(vec![stco(&[10])]);
        let seen = Cell::new(0u64);
        let out = rebuild_moov(&m, &|o, len| {
            seen.set(len);
            Ok(o + len)
        })
        .unwrap();
        assert_eq!(seen.get(), out.len() as u64);
        assert_eq!(table_entries(&out, b"stco"), vec![10 + out.len() as u64]);
    }

    #[test]
    fn temp_name_keeps_the_partial_suffix() {
        let p = Path::new("/v/Cobblify/a b.partial.mp4");
        assert_eq!(temp_path(p), Path::new("/v/Cobblify/a b.partial.ix.partial.mp4"));
    }
}
