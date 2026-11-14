"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import styles from "./landing.module.css";
import { brokerLinks, landingCopy, type LandingLocale } from "./landing-copy";

type Copy = (typeof landingCopy)[LandingLocale];

export function LandingPage({ installOnly = false }: { installOnly?: boolean }) {
  const [locale, setLocale] = useState<LandingLocale>("en");
  const copy: Copy = landingCopy[locale];

  useEffect(() => {
    const previousLanguage = document.documentElement.lang;
    document.documentElement.lang = locale;
    return () => { document.documentElement.lang = previousLanguage; };
  }, [locale]);

  if (installOnly) {
    return <InstallGuide copy={copy} />;
  }

  return <main className={styles.site}>
    <header className={styles.header}>
      <Link className={styles.brand} href="/" aria-label="AlgoTrade Hub home"><span className={styles.brandMark}>A</span><span>AlgoTrade <i>Hub</i></span></Link>
      <nav className={styles.nav} aria-label="Main navigation">
        <a href="#platform">{copy.navFeatures}</a><a href="#brokers">{copy.navBrokers}</a><a href="#plans">{copy.navPlans}</a>
      </nav>
      <div className={styles.headerActions}>
        <button className={styles.language} onClick={() => setLocale(locale === "en" ? "hi" : "en")} aria-label={copy.langLabel}>{copy.langName}</button>
        <Link className={styles.headerCta} href="/app">{copy.navSignIn}<span aria-hidden="true">↗</span></Link>
      </div>
    </header>

    <section className={styles.hero}>
      <div className={styles.heroCopy}>
        <div className={styles.eyebrow}><span />{copy.eyebrow}</div>
        <h1>{copy.heroOne}<br /><em>{copy.heroTwo}</em></h1>
        <p className={styles.heroBody}>{copy.heroBody}</p>
        <div className={styles.heroActions}><Link className={styles.primaryButton} href="/app">{copy.primaryCta}<span>→</span></Link><Link className={styles.secondaryButton} href="/install"><span className={styles.chromeGlyph}>◈</span>{copy.secondaryCta}</Link></div>
        <p className={styles.disclosure}><span>i</span>{copy.heroNote}</p>
        <div className={styles.trustLine}><span>01</span> MOCK-FIRST <b /> NO BROKER LINK <b /> LIVE ORDERS OFF</div>
      </div>

      <div className={styles.preview} aria-label="Illustrative mock workspace preview">
        <div className={styles.previewGlow} />
        <div className={styles.previewWindow}>
          <div className={styles.previewTop}><div className={styles.windowDots}><i /><i /><i /></div><span>ALGOTRADE HUB <b>·</b> {copy.mockLabel}</span><small><i /> LOCAL DEMO</small></div>
          <div className={styles.previewBody}>
            <div className={styles.previewHeading}><div><span>{copy.mockData}</span><h2>{copy.mockTitle}</h2></div><b className={styles.mockBadge}>{copy.mockOnly}</b></div>
            <div className={styles.chartCard}>
              <div className={styles.chartHead}><span>NIFTY 50 <small>· SAMPLE INSTRUMENT</small></span><span className={styles.chartTag}>{copy.mockChart}</span></div>
              <svg className={styles.chart} viewBox="0 0 520 148" role="img" aria-label={copy.mockChart} preserveAspectRatio="none"><defs><linearGradient id="chartFill" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor="#50d8b0" stopOpacity=".25" /><stop offset="1" stopColor="#50d8b0" stopOpacity="0" /></linearGradient></defs><path d="M0 75 C25 55 45 92 65 68 S95 60 115 77 S146 98 164 70 S194 56 215 74 S244 89 266 66 S296 54 320 75 S349 96 372 72 S405 60 427 76 S457 89 480 68 S505 66 520 76 V148 H0Z" fill="url(#chartFill)"/><path d="M0 75 C25 55 45 92 65 68 S95 60 115 77 S146 98 164 70 S194 56 215 74 S244 89 266 66 S296 54 320 75 S349 96 372 72 S405 60 427 76 S457 89 480 68 S505 66 520 76" fill="none" stroke="#55d4ad" strokeWidth="2.5" vectorEffect="non-scaling-stroke"/><path d="M0 36H520M0 74H520M0 112H520" stroke="#ffffff12" strokeDasharray="3 7" /></svg>
              <div className={styles.chartFoot}><span>ILLUSTRATION ONLY</span><span>NO LIVE PRICES · NO SIGNAL</span></div>
            </div>
            <div className={styles.previewCards}><div className={styles.miniCard}><span>{copy.mockHealth}</span><b><i className={styles.statusDot} />{copy.mockDisconnected}</b><small>Broker adapter unavailable</small></div><div className={styles.miniCard}><span>{copy.mockRisk}</span><b>Quantity · stop · risk</b><small>{copy.mockRiskBody}</small></div></div>
            <div className={styles.journalCard}><span className={styles.journalIcon}>✳</span><div><b>{copy.mockJournal}</b><small>{copy.mockJournalBody}</small></div><span className={styles.journalArrow}>↗</span></div>
          </div>
        </div>
        <div className={styles.floatingNote}><span>✓</span><div><b>One clear workflow</b><small>Practice · review · learn</small></div></div>
      </div>
      <div className={styles.heroBottom}><span>BUILT FOR INDIAN MARKETS</span><span>·</span><span>NSE + BSE ROADMAP</span><span>·</span><span>PAPER MODE FIRST</span></div>
    </section>

    <section className={styles.introBand}><div className={styles.introNumber}>01 / THE IDEA</div><div><h2>{copy.stripTitle}</h2><p>{copy.stripBody}</p></div><Link href="/app" className={styles.textLink}>{copy.freeCta}<span>↗</span></Link></section>

    <section className={styles.section} id="platform">
      <div className={styles.sectionHeading}><div><div className={styles.sectionEyebrow}>{copy.featureEyebrow}</div><h2>{copy.featureTitle}</h2></div><p>{copy.featureBody}</p></div>
      <div className={styles.featureGrid}>{copy.features.map((feature, index) => <article className={styles.feature} key={feature.icon}><div className={styles.featureTop}><span>{feature.icon}</span><small>{feature.status}</small></div><div className={styles.featureIcon}>{["↗", "⌁", "⛨", "◷", "▤", "◈"][index]}</div><h3>{feature.title}</h3><p>{feature.body}</p></article>)}</div>
    </section>

    <section className={styles.brokerSection} id="brokers">
      <div className={styles.brokerIntro}><div className={styles.sectionEyebrow}>{copy.brokerEyebrow}</div><h2>{copy.brokerTitle}</h2><p>{copy.brokerBody}</p><div className={styles.roadmapNote}><span>!</span>{copy.brokerFoot}</div></div>
      <div className={styles.brokerCards}>{brokerLinks.map((broker, index) => <a className={styles.brokerCard} href={broker.url} target="_blank" rel="noreferrer" key={broker.name}><span className={`${styles.brokerLogo} ${index % 2 ? styles.brokerLogoAlt : ""}`}>{broker.name.slice(0, 1)}</span><span className={styles.brokerText}><b>{broker.name}</b><small>{broker.api}</small></span><span className={styles.plannedBadge}>{copy.planned}</span><span className={styles.external}>↗</span></a>)}</div>
    </section>

    <section className={styles.processSection}><div className={styles.sectionHeading}><div><div className={styles.sectionEyebrow}>{copy.flowEyebrow}</div><h2>{copy.flowTitle}</h2></div><Link className={styles.secondaryDark} href="/install">{copy.installCta}<span>→</span></Link></div><div className={styles.steps}>{copy.steps.map((step) => <article className={styles.step} key={step.number}><span>{step.number}</span><div><h3>{step.title}</h3><p>{step.body}</p></div></article>)}</div></section>

    <section className={styles.plansSection} id="plans"><div className={styles.sectionHeading}><div><div className={styles.sectionEyebrow}>{copy.planEyebrow}</div><h2>{copy.planTitle}</h2></div><p>{copy.planBody}</p></div><div className={styles.planGrid}><article className={`${styles.plan} ${styles.freePlan}`}><div className={styles.planTop}><span>{copy.freeName}</span><span className={styles.planBadge}>AVAILABLE IN PREVIEW</span></div><h3>{copy.freePrice}</h3><p>{copy.freeLabel}</p><ul>{copy.freeBullets.map((item) => <li key={item}><span>✓</span>{item}</li>)}</ul><Link className={styles.primaryButton} href="/app">{copy.freeCta}<span>→</span></Link></article><article className={`${styles.plan} ${styles.premiumPlan}`}><div className={styles.planTop}><span>{copy.premiumName}</span><span className={styles.gatedBadge}>{copy.premiumBadge}</span></div><h3>{copy.premiumPrice}</h3><p>{copy.premiumLabel}</p><ul>{copy.premiumBullets.map((item) => <li key={item}><span>·</span>{item}</li>)}</ul><button className={styles.disabledButton} disabled>{copy.premiumCta}</button></article></div></section>

    <section className={styles.installBanner}><div><div className={styles.sectionEyebrow}>CHROME EXTENSION</div><h2>{copy.installTitle}</h2><p>{copy.installBody}</p><small>{copy.installNote}</small></div><div className={styles.installActions}><Link className={styles.primaryButton} href="/install">{copy.installCta}<span>→</span></Link><a className={styles.downloadLink} href="/downloads/algotrade-hub-extension-preview.zip" download>{copy.installZip} ↓</a></div></section>

    <section className={styles.faqSection}><div><div className={styles.sectionEyebrow}>CLEAR ANSWERS</div><h2>{copy.faqTitle}</h2><p>No hidden broker connection or live order capability.</p></div><div className={styles.faqList}>{copy.faqs.map((faq) => <details key={faq.q}><summary>{faq.q}<span>+</span></summary><p>{faq.a}</p></details>)}</div></section>

    <footer className={styles.footer}><div className={styles.footerTop}><Link className={styles.brand} href="/"><span className={styles.brandMark}>A</span><span>AlgoTrade <i>Hub</i></span></Link><span>{copy.footerLine}</span><Link href="/app">{copy.navSignIn} ↗</Link></div><p>{copy.footerDisclaimer}</p><div className={styles.footerBottom}><span>© {new Date().getFullYear()} ALGOTRADE HUB · LOCAL PREVIEW</span><span>Mock only · No live trading · No investment advice</span></div></footer>
  </main>;
}

function InstallGuide({ copy }: { copy: Copy }) {
  return <main className={`${styles.site} ${styles.installSite}`}><header className={styles.header}><Link className={styles.brand} href="/"><span className={styles.brandMark}>A</span><span>AlgoTrade <i>Hub</i></span></Link><Link className={styles.backLink} href="/">{copy.backHome} ↗</Link></header><section className={styles.installHero}><div className={styles.sectionEyebrow}>CHROME · LOCAL DEVELOPER PREVIEW</div><h1>{copy.installPageTitle}</h1><p>{copy.installPageIntro}</p><a className={styles.primaryButton} href="/downloads/algotrade-hub-extension-preview.zip" download>{copy.downloadNow}<span>↓</span></a><small>{copy.installNote}</small></section><section className={styles.installDetails}><article><div className={styles.sectionEyebrow}>SETUP</div><h2>{copy.installStepsTitle}</h2><ol>{copy.installSteps.map((step) => <li key={step}>{step}</li>)}</ol><p className={styles.setupPath}>{copy.localSetup}</p></article><article className={styles.boundary}><div className={styles.sectionEyebrow}>IMPORTANT</div><h2>{copy.installBoundaryTitle}</h2><p>{copy.installBoundary}</p><Link className={styles.textLink} href="/app">{copy.primaryCta} ↗</Link></article></section><footer className={styles.footer}><div className={styles.footerTop}><Link href="/">AlgoTrade Hub</Link><span>Local preview · mock mode only</span></div><p>{copy.footerDisclaimer}</p></footer></main>;
}
