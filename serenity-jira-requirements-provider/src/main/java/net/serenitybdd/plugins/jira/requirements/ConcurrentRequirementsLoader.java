package net.serenitybdd.plugins.jira.requirements;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import net.serenitybdd.plugins.jira.domain.IssueSummary;
import net.thucydides.core.requirements.model.Requirement;
import net.thucydides.core.util.EnvironmentVariables;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static net.serenitybdd.plugins.jira.requirements.JIRARequirementsConfiguration.JIRA_MAX_THREADS;

/**
 * Created by john on 28/01/2016.
 */
public class ConcurrentRequirementsLoader implements RequirementsLoader {

    private final ListeningExecutorService executorService;
    private final EnvironmentVariables environmentVariables;
    private final JIRARequirementsProvider requirementsProvider;
    private final RequirementsAdaptor adaptor;

    private final AtomicInteger queueSize = new AtomicInteger(0);
    static int DEFAULT_MAX_THREADS = 16;

    public ConcurrentRequirementsLoader(EnvironmentVariables environmentVariables, JIRARequirementsProvider requirementsProvider) {
        this.environmentVariables = environmentVariables;
        this.requirementsProvider = requirementsProvider;
        this.executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(getMaxJobs()));
        this.adaptor = new RequirementsAdaptor(environmentVariables);
    }

    private final org.slf4j.Logger logger = LoggerFactory.getLogger(ConcurrentRequirementsLoader.class);

    public List<Requirement> loadFrom(List<IssueSummary> rootRequirementIssues) {
        final List<Requirement> requirements = Collections.synchronizedList(new ArrayList<Requirement>());

        long t0 = System.currentTimeMillis();
        logger.debug("Loading {} requirements", rootRequirementIssues.size());
        for (final IssueSummary issueSummary : rootRequirementIssues) {
            final ListenableFuture<IssueSummary> future = executorService.submit(new Callable<IssueSummary>() {
                @Override
                public IssueSummary call() throws Exception {
                    return issueSummary;
                }
            });
            future.addListener(new Runnable() {
                @Override
                public void run() {
                    try {
                        queueSize.incrementAndGet();
                        Requirement requirement = adaptor.requirementFrom(future.get());
                        List<Requirement> childRequirements = requirementsProvider.findChildrenFor(requirement, 0);
                        requirements.add(requirement.withChildren(childRequirements));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    }
                }
            }, MoreExecutors.newDirectExecutorService());
            future.addListener(new Runnable() {
                @Override
                public void run() {
                    waitTillQueueNotEmpty();
                    queueSize.decrementAndGet();
                }
            }, executorService);

        }
        waitTillEmpty();
        logger.debug("Loading requirements done in {} ms", System.currentTimeMillis() - t0);

        return requirements;
    }

    private void waitTillQueueNotEmpty() {
        while (queueSize.get() == 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }
    }

    private void waitTillEmpty() {
        while (queueSize.get() > 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }
    }

    private int getMaxJobs() {
        return environmentVariables.getPropertyAsInteger(JIRA_MAX_THREADS.getName(),DEFAULT_MAX_THREADS);
    }
}
