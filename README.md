dropwizard-webjars-resource
===========================

Resource for use in Dropwizard that makes it a lot easier to work with [WebJars](http://www.webjars.org).

Regular java code doesn't need to know or care about what version of a dependency it is using. It simply imports the
class by name and goes on about its business. Why shouldn't your front-end development work the same way? This resource
automatically injects version information for you.

Getting Started
---------------

Just add this maven dependency to get started:
```xml
<dependency>
    <groupId>com.bazaarvoice.dropwizard</groupId>
    <artifactId>dropwizard-webjars-resource</artifactId>
    <version>0.1.9</version>
</dependency>
```

Add the resource to your environment:
```java
public class SampleService extends Service<...> {
    public static void main(String[] args)
            throws Exception {
        new SampleService().run(args);
    }

    @Override
    public void initialize(Bootstrap<...> bootstrap) {
        ...
    }

    @Override
    public void run(... cfg, Environment env)
            throws Exception {
        env.addResource(new WebJarResource());
    }
}
```

Now reference your WebJar omitting version information:
```html
<script src="/webjars/bootstrap/js/bootstrap.min.js"></script>
```
