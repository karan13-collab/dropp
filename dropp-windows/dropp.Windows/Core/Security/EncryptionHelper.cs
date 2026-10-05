using System;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

namespace dropp.Windows.Core.Security
{
    public static class EncryptionHelper
    {
        // Generates a random 256-bit secure key for pairing
        public static string GeneratePairingKey()
        {
            var key = new byte[32];
            RandomNumberGenerator.Fill(key);
            return Convert.ToBase64String(key);
        }

        public static string Encrypt(string plainText, string base64Key)
        {
            byte[] key = Convert.FromBase64String(base64Key);
            byte[] plainBytes = Encoding.UTF8.GetBytes(plainText);

            // 1. Generate a random 12-byte Nonce (Initialization Vector)
            byte[] nonce = new byte[12];
            RandomNumberGenerator.Fill(nonce);

            byte[] cipherBytes = new byte[plainBytes.Length];
            byte[] tag = new byte[16]; // 128-bit authentication tag

            using (var aes = new AesGcm(key, 16))
            {
                aes.Encrypt(nonce, plainBytes, cipherBytes, tag);
            }

            // 2. Combine to match Android's format: [Nonce (12)] + [CipherText] + [Tag (16)]
            byte[] result = new byte[nonce.Length + cipherBytes.Length + tag.Length];
            Buffer.BlockCopy(nonce, 0, result, 0, nonce.Length);
            Buffer.BlockCopy(cipherBytes, 0, result, nonce.Length, cipherBytes.Length);
            Buffer.BlockCopy(tag, 0, result, nonce.Length + cipherBytes.Length, tag.Length);

            return Convert.ToBase64String(result);
        }

        public static string Decrypt(string base64Payload, string base64Key)
        {
            byte[] key = Convert.FromBase64String(base64Key);
            byte[] payload = Convert.FromBase64String(base64Payload);

            // 1. Unpack the Android format
            byte[] nonce = payload.Take(12).ToArray();
            byte[] tag = payload.Skip(payload.Length - 16).ToArray();
            byte[] cipherBytes = payload.Skip(12).Take(payload.Length - 12 - 16).ToArray();

            byte[] plainBytes = new byte[cipherBytes.Length];

            using (var aes = new AesGcm(key, 16))
            {
                aes.Decrypt(nonce, cipherBytes, tag, plainBytes);
            }

            return Encoding.UTF8.GetString(plainBytes);
        }
    }
}