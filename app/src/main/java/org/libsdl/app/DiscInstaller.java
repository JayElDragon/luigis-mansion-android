package org.libsdl.app;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** Transactional installation: the existing disc remains usable until the copy is complete. */
final class DiscInstaller {
    interface Progress { void update(long done,long total); }
    static File install(InputStream source,File dir,String suppliedName,long total,Progress progress) throws IOException {
        String name=new File(suppliedName.replace('\\','/')).getName();
        String lower=name.toLowerCase(Locale.ROOT);
        if(!lower.endsWith(".iso") && !lower.endsWith(".rvz")) throw new IOException("Choose an ISO or RVZ disc image.");
        if(source==null) throw new IOException("Cannot open the selected file.");
        if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException("App storage is unavailable.");
        if(total>0 && total>dir.getUsableSpace()-64L*1024*1024) throw new IOException("Not enough free space to install this disc.");
        File tmp=File.createTempFile("install-",".part",dir), target=new File(dir,name);
        try {
            long done=0,last=0;
            try(FileOutputStream out=new FileOutputStream(tmp)) {
                byte[] buffer=new byte[1024*1024]; int n;
                while((n=source.read(buffer))!=-1) {
                    if(n==0) continue;
                    out.write(buffer,0,n); done+=n;
                    long now=System.nanoTime();
                    if(now-last>150_000_000L) { progress.update(done,total); last=now; }
                }
                out.getFD().sync();
            }
            if(done<32 || (total>=0 && done!=total)) throw new IOException("Incomplete disc image. The previous installation was kept.");
            try(RandomAccessFile input=new RandomAccessFile(tmp,"r")) {
                if(lower.endsWith(".iso")) {
                    if(input.readUnsignedByte()!='G' || input.readUnsignedByte()!='L' || input.readUnsignedByte()!='M')
                        throw new IOException("This ISO is not a Luigi's Mansion GameCube disc.");
                    input.seek(0x1c);
                    if(input.readInt()!=0xC2339F3D) throw new IOException("Invalid GameCube ISO header.");
                    input.seek(0x424);
                    long fstOffset=Integer.toUnsignedLong(input.readInt()), fstSize=Integer.toUnsignedLong(input.readInt());
                    if(fstOffset<0x440 || fstSize<12 || fstOffset+fstSize>done)
                        throw new IOException("The ISO is truncated or has an invalid file table.");
                } else if(input.readInt()!=0x52565A01) throw new IOException("Invalid RVZ header.");
            }
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
