using System;
using System.IO;
using System.Security.Cryptography;
using Microsoft.Owin.Security.DataProtection;

namespace FLS.Server.MonoHost
{
    // Cross-platform replacement for DpapiDataProtector / MachineKeyDataProtectionProvider,
    // which depend on Windows-only APIs that Mono can't load.
    // Demo-grade: uses a fixed key derived from a constant. NOT for production.
    public class SimpleDataProtectionProvider : IDataProtectionProvider
    {
        public IDataProtector Create(params string[] purposes)
        {
            return new SimpleDataProtector(purposes);
        }
    }

    public class SimpleDataProtector : IDataProtector
    {
        private readonly byte[] _key;

        public SimpleDataProtector(string[] purposes)
        {
            // Derive a 32-byte key from a fixed demo secret + purposes.
            var seed = "FLS-Mono-Demo-DataProtector-v1|" + string.Join("|", purposes ?? new string[0]);
            using (var sha = SHA256.Create()) { _key = sha.ComputeHash(System.Text.Encoding.UTF8.GetBytes(seed)); }
        }

        public byte[] Protect(byte[] userData)
        {
            if (userData == null) throw new ArgumentNullException("userData");
            using (var aes = Aes.Create())
            {
                aes.Key = _key;
                aes.GenerateIV();
                using (var ms = new MemoryStream())
                {
                    ms.Write(aes.IV, 0, aes.IV.Length);
                    using (var enc = aes.CreateEncryptor())
                    using (var cs = new CryptoStream(ms, enc, CryptoStreamMode.Write))
                    {
                        cs.Write(userData, 0, userData.Length);
                    }
                    return ms.ToArray();
                }
            }
        }

        public byte[] Unprotect(byte[] protectedData)
        {
            if (protectedData == null) throw new ArgumentNullException("protectedData");
            using (var aes = Aes.Create())
            {
                aes.Key = _key;
                var iv = new byte[aes.BlockSize / 8];
                Array.Copy(protectedData, 0, iv, 0, iv.Length);
                aes.IV = iv;
                using (var ms = new MemoryStream())
                {
                    using (var dec = aes.CreateDecryptor())
                    using (var cs = new CryptoStream(new MemoryStream(protectedData, iv.Length, protectedData.Length - iv.Length), dec, CryptoStreamMode.Read))
                    {
                        cs.CopyTo(ms);
                    }
                    return ms.ToArray();
                }
            }
        }
    }
}
