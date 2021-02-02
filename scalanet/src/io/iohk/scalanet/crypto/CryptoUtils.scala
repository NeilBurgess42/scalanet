package io.iohk.scalanet.crypto

import java.math.BigInteger
import java.security._
import java.security.cert.X509Certificate
import java.security.spec.{ECGenParameterSpec, PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Date
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.Extension
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.{ECDomainParameters, ECKeyGenerationParameters}
import org.bouncycastle.crypto.util.{PrivateKeyInfoFactory, SubjectPublicKeyInfoFactory}
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.{ECParameterSpec, ECPublicKeySpec}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import scodec.bits.BitVector

import scala.util.Try

private[scalanet] object CryptoUtils {

  /**
    * Elliptic Curve Groups(ECDHE) recommended by TLS 1.3
    */
  sealed abstract class SupportedCurves(val name: String)
  case object Secp256r1 extends SupportedCurves("secp256r1")
  case object Secp384r1 extends SupportedCurves("secp384r1")
  case object Secp521r1 extends SupportedCurves("secp521r1")

  private val curveName = "secp256k1"

  type SignatureBytes = Array[Byte]

  abstract class SignatureScheme(val name: String)
  case object SHA256withECDSA extends SignatureScheme("SHA256withECDSA")

  private val usedKeyScheme = "EC"

  private val PROVIDER = new BouncyCastleProvider()

  private val curveParams: X9ECParameters = SECNamedCurves.getByName(curveName)

  private val curve: ECDomainParameters =
    new ECDomainParameters(curveParams.getCurve, curveParams.getG, curveParams.getN, curveParams.getH)

  private def getEcdsaSignature: Signature = {
    Signature.getInstance(SHA256withECDSA.name, PROVIDER)
  }

  private def getEcKeyFactory: KeyFactory = {
    KeyFactory.getInstance(usedKeyScheme, PROVIDER)
  }

  def generateKeyPair(secureRandom: SecureRandom): AsymmetricCipherKeyPair = {
    val generator = new ECKeyPairGenerator
    generator.init(new ECKeyGenerationParameters(curve, secureRandom))
    generator.generateKeyPair()
  }

  def genEcKeyPair(secureRandom: SecureRandom, curveName: String): KeyPair = {
    val ecSpec = new ECGenParameterSpec(curveName)
    val g = KeyPairGenerator.getInstance("EC", PROVIDER)
    g.initialize(ecSpec, secureRandom)
    g.generateKeyPair();
  }

  def genTlsSupportedKeyPair(secureRandom: SecureRandom, curveName: SupportedCurves): KeyPair = {
    genEcKeyPair(secureRandom, curveName.name)
  }

  def signEcdsa(data: Array[Byte], privateKey: PrivateKey, secureRandom: SecureRandom): SignatureBytes = {
    val ecdsaSign = getEcdsaSignature
    ecdsaSign.initSign(privateKey, secureRandom)
    ecdsaSign.update(data);
    ecdsaSign.sign();
  }

  def verifyEcdsa(data: Array[Byte], signature: SignatureBytes, publicKey: java.security.PublicKey): Boolean =
    Try {
      val ecdsaVerify = getEcdsaSignature
      ecdsaVerify.initVerify(publicKey)
      ecdsaVerify.update(data)
      ecdsaVerify.verify(signature)
    }.fold(_ => false, result => result)

  def convertBcToJceKeyPair(bcKeyPair: AsymmetricCipherKeyPair): KeyPair = {
    val pkcs8Encoded = PrivateKeyInfoFactory.createPrivateKeyInfo(bcKeyPair.getPrivate).getEncoded()
    val pkcs8KeySpec = new PKCS8EncodedKeySpec(pkcs8Encoded)
    val spkiEncoded = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(bcKeyPair.getPublic).getEncoded()
    val spkiKeySpec = new X509EncodedKeySpec(spkiEncoded)
    val keyFac = getEcKeyFactory
    new KeyPair(keyFac.generatePublic(spkiKeySpec), keyFac.generatePrivate(pkcs8KeySpec))
  }

  def getSecp256k1KeyFromBytes(bytes: Array[Byte]): Try[PublicKey] = Try {
    val ecPoint = curve.getCurve.decodePoint(bytes)
    val spec = new ECParameterSpec(curveParams.getCurve, curveParams.getG, curveParams.getN)
    val pubKeySpec = new ECPublicKeySpec(ecPoint, spec)
    val keyFac = getEcKeyFactory
    keyFac.generatePublic(pubKeySpec)
  }

  def getBouncyCastlePubKey(bytes: Array[Byte], algorithm: String): Try[PublicKey] = Try {
    val spec = new X509EncodedKeySpec(bytes)
    val keyFac = KeyFactory.getInstance(algorithm, PROVIDER)
    keyFac.generatePublic(spec)
  }

  def getEcPublicKey(publicKey: PublicKey): Try[BitVector] = Try {
    BitVector(publicKey.asInstanceOf[ECPublicKey].getQ.getEncoded(false))
  }

  def buildCertificateWithExtensions(
      connectionKeyPair: KeyPair,
      random: SecureRandom,
      extensions: List[Extension],
      beforeDate: Date,
      afterDate: Date,
      signatureScheme: SignatureScheme
  ): X509Certificate = {
    val name = "scalanet-tls"
    val sn = new BigInteger(64, random)
    val owner = new X500Name("CN=" + name);
    val sub = SubjectPublicKeyInfo.getInstance(connectionKeyPair.getPublic.getEncoded)
    val certificateBuilder = new X509v3CertificateBuilder(owner, sn, beforeDate, afterDate, owner, sub)

    extensions.foreach { extension =>
      certificateBuilder.addExtension(extension.oid, extension.isCritical, extension.value)
    }

    val signer = new JcaContentSignerBuilder(signatureScheme.name).build(connectionKeyPair.getPrivate);

    val ca = certificateBuilder.build(signer)

    val cert = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(ca)

    cert
  }
}
