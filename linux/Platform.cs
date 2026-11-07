// Starwire microphone receiver for Linux: the parts of SecureMic.cs that only Windows has, on
// .NET's own cryptography (GPL-3.0, part of Starwire).
//
// ../windows/SecureMic.cs is built with STARWIRE_LINUX defined, which leaves out CNG's AES-GCM, CNG's
// ECDH and DPAPI. This file gives the same classes and methods, so the format, the pairing and the
// endpoint's rules stay one shared piece of code, tested by the same tests and vectors on both.
// On Linux .NET's AES-GCM and ECDH come from OpenSSL.

using EcdhKey = System.Security.Cryptography.ECDiffieHellman;
using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

// AES-256-GCM, with the API SecureMic.cs's Windows class has. The name is kept so the shared code
// builds as it is. One instance per key, used by one thread at a time; nothing is allocated per
// packet.
sealed class CngAesGcm : IDisposable
{
    public const int MaxAad = 80, MaxData = 2048;

    AesGcm aead;
    // Decrypted into first, so output is left as it was when the tag doesn't check out (.NET
    // clears the destination on a failure)
    readonly byte[] scratch = new byte[MaxData];

    public CngAesGcm(byte[] key32)
    {
        if (key32 == null || key32.Length != 32) {
            throw new ArgumentException("AES-256 needs a 32-byte key");
        }
        aead = new AesGcm(key32, 16);
    }

    // Encrypts input into output (the ciphertext, then the 16-byte tag); returns the bytes written
    public int Seal(byte[] nonce, int nonceAt, byte[] aad, int aadAt, int aadLength, byte[] input, int inAt, int inLength, byte[] output, int outAt)
    {
        if (aead == null) {
            throw new ObjectDisposedException("CngAesGcm");
        }
        if (aadLength > MaxAad || inLength > MaxData) {
            throw new ArgumentException("too long to encrypt");
        }
        // The nonce and the AAD can overlap the output (Swmc.Audio writes the header there first),
        // so they're read before anything is written
        Span<byte> n = stackalloc byte[12];
        Span<byte> a = stackalloc byte[MaxAad];
        nonce.AsSpan(nonceAt, 12).CopyTo(n);
        aad.AsSpan(aadAt, aadLength).CopyTo(a);
        input.AsSpan(inAt, inLength).CopyTo(scratch);
        Span<byte> tag = stackalloc byte[16];
        aead.Encrypt(n, scratch.AsSpan(0, inLength), output.AsSpan(outAt, inLength), tag, a.Slice(0, aadLength));
        tag.CopyTo(output.AsSpan(outAt + inLength, 16));
        return inLength + 16;
    }

    // Decrypts the ciphertext and tag into output; returns the plaintext's length, or -1 if it
    // isn't authentic (output is then left as it was)
    public int Open(byte[] nonce, int nonceAt, byte[] aad, int aadAt, int aadLength, byte[] input, int inAt, int inLength, byte[] output, int outAt)
    {
        int dataLength = inLength - 16;
        if (aead == null || dataLength < 0 || aadLength > MaxAad || dataLength > MaxData) {
            return -1;
        }
        try {
            aead.Decrypt(nonce.AsSpan(nonceAt, 12), input.AsSpan(inAt, dataLength), input.AsSpan(inAt + dataLength, 16),
                scratch.AsSpan(0, dataLength), aad.AsSpan(aadAt, aadLength));
        }
        catch (AuthenticationTagMismatchException) {
            return -1;
        }
        scratch.AsSpan(0, dataLength).CopyTo(output.AsSpan(outAt));
        return dataLength;
    }

    public void Dispose()
    {
        if (aead != null) {
            aead.Dispose();
            aead = null;
            // Don't leave audio behind in the scratch memory
            CryptographicOperations.ZeroMemory(scratch);
        }
    }
}

// ECDH P-256 on .NET's ECDiffieHellman, with the methods SecureMic.cs's CNG section has
static partial class Swmc
{
    public static EcdhKey NewKey() { return ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256); }

    // A key from fixed numbers, for the known-answer tests
    public static EcdhKey FixedKey(byte[] d, byte[] x, byte[] y)
    {
        ECParameters p = new ECParameters();
        p.Curve = ECCurve.NamedCurves.nistP256;
        p.D = (byte[]) d.Clone();
        p.Q = new ECPoint { X = (byte[]) x.Clone(), Y = (byte[]) y.Clone() };
        return ECDiffieHellman.Create(p);
    }

    public static byte[] OwnPoint(EcdhKey mine)
    {
        ECParameters p = mine.ExportParameters(false);
        byte[] point = new byte[PointLength];
        point[0] = 4;
        Array.Copy(p.Q.X, 0, point, 1, 32);
        Array.Copy(p.Q.Y, 0, point, 33, 32);
        return point;
    }

    // SHA-256 of the shared X coordinate, as CNG's "Hash" key derivation gives on Windows. Throws
    // CryptographicException for a point that isn't on P-256 (OpenSSL checks when it's imported).
    public static byte[] EcdhHash(EcdhKey mine, byte[] peerPoint, int at)
    {
        if (peerPoint[at] != 4) {
            throw new CryptographicException("not an uncompressed point");
        }
        ECParameters p = new ECParameters();
        p.Curve = ECCurve.NamedCurves.nistP256;
        p.Q = new ECPoint { X = Slice(peerPoint, at + 1, 32), Y = Slice(peerPoint, at + 33, 32) };
        using (ECDiffieHellman peer = ECDiffieHellman.Create(p)) {
            return mine.DeriveKeyFromHash(peer.PublicKey, HashAlgorithmName.SHA256);
        }
    }
}

// The pairing keys as the pairings file keeps them. Linux has no DPAPI, so the file is protected
// the way SSH keys are: only this user can read it (the file is 0600, its folder 0700). The keys
// aren't encrypted at rest: anyone who can read this user's files, root included, can read them.
//
// Each stored key carries a 16-byte check (HMAC-SHA256 with the entropy as key, cut short), so a
// key stored for another purpose, or a damaged one, is refused like a key DPAPI can't decrypt.
static class KeyProtection
{
    const int CheckLength = 16;

    public static byte[] Protect(byte[] key, byte[] entropy)
    {
        byte[] check = Swmc.Mac16(entropy, key, 0, key.Length);
        return Swmc.Concat(key, check);
    }

    // Throws CryptographicException for a key that wasn't stored with this entropy
    public static byte[] Unprotect(byte[] stored, byte[] entropy)
    {
        if (stored.Length < CheckLength) {
            throw new CryptographicException("damaged key");
        }
        byte[] key = Swmc.Slice(stored, 0, stored.Length - CheckLength);
        byte[] check = Swmc.Mac16(entropy, key, 0, key.Length);
        if (!Swmc.ConstantTimeEquals(check, 0, stored, key.Length, CheckLength)) {
            throw new CryptographicException("a key stored for something else");
        }
        return key;
    }

    const UnixFileMode Folder = UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute;
    const UnixFileMode Private = UnixFileMode.UserRead | UnixFileMode.UserWrite;

    // The folder, made 0700 if it's new. One that's already there is made 0700 too if it's
    // Starwire's own (it only ever holds the receiver's files).
    public static void CreateFolder(string path)
    {
        if (!Directory.Exists(path)) {
            Directory.CreateDirectory(path, Folder);
        }
        else if (Path.GetFileName(Path.TrimEndingDirectorySeparator(path)) == "Starwire") {
            File.SetUnixFileMode(path, Folder);
        }
    }

    // Written 0600 from the start, so the keys are never readable by anyone else even for a moment
    public static void WriteFile(string path, string text)
    {
        FileStreamOptions options = new FileStreamOptions();
        options.Mode = FileMode.Create;
        options.Access = FileAccess.Write;
        options.UnixCreateMode = Private;
        using (FileStream f = new FileStream(path, options)) {
            // An earlier file of that name could have had other permissions
            File.SetUnixFileMode(f.SafeFileHandle, Private);
            byte[] bytes = new UTF8Encoding(false).GetBytes(text);
            f.Write(bytes, 0, bytes.Length);
            f.Flush(true);
        }
    }
}
