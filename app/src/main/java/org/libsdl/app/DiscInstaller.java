package org.libsdl.app;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** Transactional installation: the existing disc remains usable until the copy is complete. */
final class DiscInstaller {
    interface Progress { void update(long done,long total); }
    /** Disc versions this build of the game runs. The game engine library is built for the USA release. */
    static final String SUPPORTED_ID = "GLME01";

    /** The 6-letter game ID at the start of a GameCube ISO (e.g. GLME01), or null. */
    static String gameId(File f) {
        try(RandomAccessFile in=new RandomAccessFile(f,"r")) {
            byte[] b=new byte[6];
            if(isRvz(in)) in.seek(0x58);  // RVZ: copy of the disc header inside its second header
            else in.seek(0);
            in.readFully(b);
            for(byte x:b) if(x<0x20 || x>0x7e) return null;
            return new String(b,"US-ASCII");
        } catch(Exception e) { return null; }
    }

    static boolean isRvz(RandomAccessFile in) throws IOException {
        in.seek(0);
        return in.readInt()==0x52565A01;  // "RVZ" + 1
    }

    static String regionName(String id) {
        if(id==null || id.length()<4) return "unknown";
        switch(id.charAt(3)) {
            case 'E': return "USA";
            case 'P': return "Europe";
            case 'J': return "Japan";
            case 'K': return "Korea";
            default: return "other region";
        }
    }

    static String regionMessage(String id) {
        if("GLMP01".equals(id))
            return "This is the European version (GLMP01). This build of the game runs the USA version (GLME01) only for now.";
        return "This is the "+regionName(id)+" version ("+id+"). Only the USA version (GLME01) is supported.";
    }

    /** Null when the ISO is a disc this build can run; otherwise a message for the player. */
    static String check(File f,long length) {
        try(RandomAccessFile input=new RandomAccessFile(f,"r")) {
            if(length<0x440) return "The file is too small to be a GameCube disc.";
            boolean rvz=isRvz(input);
            String id=gameId(f);
            if(id==null || !id.startsWith("GLM"))
                return "This file is not a Luigi's Mansion GameCube disc.";
            int hdr=rvz?0x58:0;
            input.seek(hdr+0x1c);
            if(input.readInt()!=0xC2339F3D) return "Invalid GameCube disc header.";
            if(!rvz) {
                input.seek(0x424);
                long fstOffset=Integer.toUnsignedLong(input.readInt()), fstSize=Integer.toUnsignedLong(input.readInt());
                if(fstOffset<0x440 || fstSize<12 || fstOffset+fstSize>length)
                    return "The ISO is truncated or has an invalid file table. Make a new copy of your disc.";
            }
            if(!SUPPORTED_ID.equals(id)) return regionMessage(id);
            input.seek(hdr+7);
            int rev=input.readUnsignedByte();
            if(rev!=0) return "This disc is revision "+rev+". Only revision 0 of the USA version is supported.";
            return null;
        } catch(Exception e) { return "Could not read the disc file."; }
    }

    static File install(InputStream source,File dir,String suppliedName,long total,Progress progress) throws IOException {
        String name=new File(suppliedName.replace('\\','/')).getName();
        String lower=name.toLowerCase(Locale.ROOT);
        if(!lower.endsWith(".iso") && !lower.endsWith(".rvz")) throw new IOException("Choose an .iso or .rvz disc image.");
        if(source==null) throw new IOException("Cannot open the selected file.");
        if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("App storage is unavailable.");
        if(total>0 && total>dir.getUsableSpace()-64L*1024*1024) throw new IOException("Not enough free space to install this disc.");
        File tmp=File.createTempFile("install-",".part",dir), target=new File(dir,name);
        try {
            long done=0,last=0; boolean early=false;
            try(FileOutputStream out=new FileOutputStream(tmp)) {
                byte[] buffer=new byte[1024*1024]; int n;
                while((n=source.read(buffer))!=-1) {
                    if(n==0) continue;
                    out.write(buffer,0,n); done+=n;
                    if(!early && done>=0x60) {  // wrong game or region: stop now, not after copying 1.4 GB
                        early=true;
                        String id=gameId(tmp);
                        if(id==null || !id.startsWith("GLM")) throw new IOException("This file is not a Luigi's Mansion GameCube disc.");
                        if(!SUPPORTED_ID.equals(id)) throw new IOException(regionMessage(id));
                    }
                    long now=System.nanoTime();
                    if(now-last>150_000_000L) { progress.update(done,total); last=now; }
                }
                out.getFD().sync();
            }
            if(done<32 || (total>=0 && done!=total)) throw new IOException("Incomplete disc image. The previous installation was kept.");
            String problem=check(tmp,done);
            if(problem!=null) throw new IOException(problem);
            // Same-filesystem atomic replacement: do not delete the old installation first.
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            File[] files=dir.listFiles();
            if(files!=null) for(File old:files) {
                String oldName=old.getName().toLowerCase(Locale.ROOT);
                if(!old.equals(target) && old.isFile() && (oldName.endsWith(".iso") || oldName.endsWith(".rvz"))) old.delete();
            }
            progress.update(done,done);
            return target;
        } finally { if(tmp.exists()) tmp.delete(); }
    }
}
