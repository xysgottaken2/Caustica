package dev.xys.vulkanrt.build
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*
class EntityHookContractTest {
 @Test void acceptsKnownSitesAndRejectsMissingOrDuplicatedCalls() {
  EntityHookContract.EXPECTED.each { owner,methods ->
   def fixture=[:]
   methods.each { name,sites -> fixture[name+'()V']=sites.collectMany { call,count -> Collections.nCopies(count,call) } }
   assertTrue(EntityHookContract.verifyCalls(owner,fixture).isEmpty())
   def key=fixture.keySet().first()
   def original=new ArrayList(fixture[key]);fixture[key]=[]
   assertFalse(EntityHookContract.verifyCalls(owner,fixture).isEmpty())
   fixture[key]=original+original
   assertFalse(EntityHookContract.verifyCalls(owner,fixture).isEmpty())
  }
 }
}
