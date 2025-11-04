from sklearn.feature_extraction.text import CountVectorizer

text = ['NLP has changed the world', 'I love NLP', 'NLP is cool', 'NLP is future']
cv = CountVectorizer(ngram_range=(2, 2))
bow = cv.fit_transform(text)
print(cv.vocabulary_)